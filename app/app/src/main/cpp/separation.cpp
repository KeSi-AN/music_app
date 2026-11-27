#include "separation.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdio>

#include "dsp.h"
#include "signalsmith-linear/fft.h"

namespace riyaz {
namespace {
constexpr double kPi = 3.14159265358979323846;

// numpy.pad(..., mode="reflect") index for position i in [-pad, n+pad)
inline long reflectIndex(long i, long n) {
    if (n <= 1) return 0;
    while (i < 0 || i >= n) {
        if (i < 0) i = -i;
        if (i >= n) i = 2 * (n - 1) - i;
    }
    return i;
}
}  // namespace

bool MdxJob::open(const std::string &pcmPath, int songRate, int nFft, int dimF) {
    FILE *f = fopen(pcmPath.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long bytes = ftell(f);
    fseek(f, 0, SEEK_SET);
    songFrames_ = bytes / 4;
    std::vector<float> l(songFrames_), r(songFrames_);
    std::vector<int16_t> buf(1 << 16);
    int64_t done = 0;
    while (done < songFrames_) {
        size_t want = size_t(std::min<int64_t>(int64_t(buf.size() / 2), songFrames_ - done)) * 2;
        size_t got = fread(buf.data(), sizeof(int16_t), want, f);
        if (got == 0) break;
        for (size_t i = 0; i + 1 < got; i += 2, ++done) {
            l[done] = buf[i] / 32768.0f;
            r[done] = buf[i + 1] / 32768.0f;
        }
    }
    fclose(f);
    songFrames_ = done;
    l.resize(done);
    r.resize(done);
    if (songRate != kSampleRate) {
        l = dsp::resample(l, songRate, kSampleRate);
        r = dsp::resample(r, songRate, kSampleRate);
    }
    frames_ = int64_t(l.size());
    mix_.resize(size_t(frames_) * 2);
    for (int64_t i = 0; i < frames_; ++i) {
        mix_[2 * i] = l[i];
        mix_[2 * i + 1] = r[i];
    }
    songRate_ = songRate;
    nFft_ = nFft;
    dimF_ = dimF;
    chunk_ = kHop * (kDimT - 1);
    trim_ = nFft / 2;
    gen_ = chunk_ - 2 * trim_;
    nChunks_ = int((frames_ + gen_ - 1) / gen_);
    voc_.assign(size_t(nChunks_) * gen_ * 2, 0.0f);
    window_.resize(nFft);
    for (int i = 0; i < nFft; ++i) window_[i] = float(0.5 - 0.5 * std::cos(2 * kPi * i / nFft));   // periodic
    return frames_ > 0;
}

void MdxJob::input(int k, float *out) {
    // chunk k covers padded positions [k*gen, k*gen + chunk) where padded = trim zeros + mix + zeros
    const long chunkStart = long(k) * gen_ - trim_;   // index into mix_ (negative = leading zeros)
    auto sample = [&](int c, long j) -> float {      // j: position inside the chunk
        long idx = chunkStart + j;
        return (idx >= 0 && idx < frames_) ? mix_[size_t(idx) * 2 + c] : 0.0f;
    };
    const int n = nFft_;
    signalsmith::linear::RealFFT<float> fft(n);
    std::vector<float> frame(n);
    std::vector<std::complex<float>> X(n / 2);
    const size_t plane = size_t(dimF_) * kDimT;
    for (int c = 0; c < 2; ++c) {
        for (int t = 0; t < kDimT; ++t) {
            // centred frame: positions t*hop - n/2 .. t*hop + n/2, reflected at the chunk edges
            for (int i = 0; i < n; ++i) {
                long j = reflectIndex(long(t) * kHop - n / 2 + i, chunk_);
                frame[i] = sample(c, j) * window_[i];
            }
            fft.fft(frame.data(), X.data());
            float *re = out + (2 * c) * plane;
            float *im = out + (2 * c + 1) * plane;
            for (int b = 0; b < dimF_; ++b) {
                std::complex<float> v = (b == 0) ? std::complex<float>(X[0].real(), 0.0f) : X[b];
                if (b < 3) v = 0;
                re[size_t(b) * kDimT + t] = v.real();
                im[size_t(b) * kDimT + t] = v.imag();
            }
        }
    }
}

void MdxJob::output(int k, const float *pred) {
    const int n = nFft_;
    signalsmith::linear::RealFFT<float> fft(n);
    std::vector<std::complex<float>> X(n / 2);
    std::vector<float> seg(n);
    const size_t plane = size_t(dimF_) * kDimT;
    const int total = n + kHop * (kDimT - 1);
    std::vector<double> y(total), wsum(total);
    for (int c = 0; c < 2; ++c) {
        std::fill(y.begin(), y.end(), 0.0);
        std::fill(wsum.begin(), wsum.end(), 0.0);
        const float *re = pred + (2 * c) * plane;
        const float *im = pred + (2 * c + 1) * plane;
        for (int t = 0; t < kDimT; ++t) {
            std::fill(X.begin(), X.end(), std::complex<float>(0, 0));
            for (int b = 0; b < dimF_ && b < n / 2; ++b) X[b] = {re[size_t(b) * kDimT + t], im[size_t(b) * kDimT + t]};
            X[0] = {X[0].real(), 0.0f};   // packed DC / Nyquist (Nyquist bin is above dimF: zero)
            fft.ifft(X.data(), seg.data());
            for (int i = 0; i < n; ++i) {
                float w = window_[i];
                y[size_t(t) * kHop + i] += double(seg[i]) / n * w;
                wsum[size_t(t) * kHop + i] += double(w) * w;
            }
        }
        // centre-cropped output position j (0..chunk) is y[j + n/2]; keep the middle gen samples
        for (int j = 0; j < gen_; ++j) {
            int p = trim_ + j + n / 2;
            double v = y[p] / std::max(wsum[p], 1e-8);
            voc_[(size_t(k) * gen_ + j) * 2 + c] = float(v);
        }
    }
}

bool MdxJob::writeBacking(const std::string &outPath, float compensate) {
    std::vector<float> l(frames_), r(frames_);
    for (int64_t i = 0; i < frames_; ++i) {
        l[i] = mix_[2 * i] - voc_[2 * i] * compensate;
        r[i] = mix_[2 * i + 1] - voc_[2 * i + 1] * compensate;
    }
    if (songRate_ != kSampleRate) {
        l = dsp::resample(l, kSampleRate, songRate_);
        r = dsp::resample(r, kSampleRate, songRate_);
    }
    FILE *f = fopen(outPath.c_str(), "wb");
    if (!f) return false;
    std::vector<int16_t> buf;
    buf.reserve(1 << 16);
    auto flush = [&]() { fwrite(buf.data(), sizeof(int16_t), buf.size(), f); buf.clear(); };
    for (int64_t i = 0; i < songFrames_; ++i) {   // exactly the song's length, so it lines up in the player
        float a = i < int64_t(l.size()) ? l[i] : 0.0f;
        float b = i < int64_t(r.size()) ? r[i] : 0.0f;
        buf.push_back(int16_t(std::clamp(a, -1.0f, 1.0f) * 32767.0f));
        buf.push_back(int16_t(std::clamp(b, -1.0f, 1.0f) * 32767.0f));
        if (buf.size() >= (1 << 16)) flush();
    }
    flush();
    bool ok = ferror(f) == 0;
    fclose(f);
    return ok;
}

}  // namespace riyaz
