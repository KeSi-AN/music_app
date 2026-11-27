#include "analysis.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstdio>
#include <memory>
#include <numeric>
#include <thread>

#include "dsp.h"
#include "signalsmith-linear/fft.h"

#define LOG_TAG "RiyazAnalysis"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace riyaz {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr float kBtcMean = -2.2279878897355596f;   // from BTC's checkpoint
constexpr float kBtcStd = 1.7191329394436938f;

// ---------------------------------------------------------------- CQT (Brown & Puckette)

struct CqtKernels {
    int nfft = 0;
    struct Kernel {
        std::vector<int> bins;
        std::vector<std::complex<float>> coef;   // conj(K)/nfft * sqrt(length)
    };
    std::vector<Kernel> kernels;
};

CqtKernels makeKernels() {
    constexpr double fmin = 32.70319566257483;   // C1
    constexpr int bins = Features::kCqtBins, bpo = 24;
    constexpr double sparsity = 0.01;
    const double sr = Features::kSampleRate;
    const double Q = 1.0 / (std::pow(2.0, 1.0 / bpo) - 1.0);

    CqtKernels K;
    double maxLen = Q * sr / fmin;
    K.nfft = 1;
    while (K.nfft < maxLen) K.nfft <<= 1;
    const int N = K.nfft;

    signalsmith::linear::Pow2FFT<double> fft(N);
    std::vector<std::complex<double>> time(N), freq(N);
    K.kernels.resize(bins);
    for (int k = 0; k < bins; ++k) {
        double f = fmin * std::pow(2.0, double(k) / bpo);
        double L = Q * sr / f;
        int lo = -int(std::floor(L / 2)), hi = int(std::ceil(L / 2));   // n in [lo, hi)
        int len = hi - lo;
        std::fill(time.begin(), time.end(), 0.0);
        std::vector<std::complex<double>> sig(len);
        double l1 = 0;
        for (int i = 0; i < len; ++i) {
            int n = lo + i;
            double w = 0.5 - 0.5 * std::cos(2 * kPi * (i + 1) / (len + 1));    // hanning(len+2)[1:-1]
            sig[i] = w * std::polar(1.0, 2 * kPi * f * n / sr);
            l1 += std::abs(sig[i]);
        }
        int start = N / 2 - len / 2;
        for (int i = 0; i < len; ++i) time[start + i] = sig[i] / l1;
        fft.fft(time.data(), freq.data());

        // keep the coefficients carrying all but `sparsity` of the L1 mass
        std::vector<int> order(N);
        std::iota(order.begin(), order.end(), 0);
        std::sort(order.begin(), order.end(), [&](int a, int b) { return std::abs(freq[a]) < std::abs(freq[b]); });
        double total = 0;
        for (auto &c : freq) total += std::abs(c);
        double cum = 0;
        auto &ker = K.kernels[k];
        std::vector<int> keep;
        for (int idx : order) {
            cum += std::abs(freq[idx]);
            if (cum / total > sparsity) keep.push_back(idx);
        }
        std::sort(keep.begin(), keep.end());
        const double scale = std::sqrt(L) / N;
        for (int idx : keep) {
            if (idx < 1 || idx >= N / 2) continue;   // real input: positive-frequency bins only (bin 0 is packed DC/Nyquist)
            ker.bins.push_back(idx);
            ker.coef.emplace_back(std::conj(freq[idx]) * scale);
        }
    }
    return K;
}

const CqtKernels &kernels() {
    static const CqtKernels K = makeKernels();
    return K;
}

void cqtFrames(const std::vector<float> &y, int first, int last, std::vector<float> &out,
               std::atomic<int> *done) {
    const CqtKernels &K = kernels();
    const int N = K.nfft, hop = Features::kCqtHop, pad = N / 2;
    signalsmith::linear::RealFFT<float> fft(N);
    std::vector<float> frame(N);
    std::vector<std::complex<float>> X(N / 2);
    const long n = long(y.size());
    for (int t = first; t < last; ++t) {
        long s0 = long(t) * hop - pad;
        for (int i = 0; i < N; ++i) {
            long idx = s0 + i;
            frame[i] = (idx >= 0 && idx < n) ? y[idx] : 0.0f;
        }
        fft.fft(frame.data(), X.data());
        float *row = &out[size_t(t) * Features::kCqtBins];
        for (int k = 0; k < Features::kCqtBins; ++k) {
            const auto &ker = K.kernels[k];
            std::complex<float> acc = 0;
            for (size_t j = 0; j < ker.bins.size(); ++j) acc += X[ker.bins[j]] * ker.coef[j];
            float mag = std::abs(acc);
            row[k] = (std::log(mag + 1e-6f) - kBtcMean) / kBtcStd;
        }
        if (done) done->fetch_add(1);
    }
}

// ---------------------------------------------------------------- onset strength

std::vector<float> onsetEnvelope(const std::vector<float> &y) {
    constexpr int nfft = 2048, hop = Features::kEnvHop;
    const long n = long(y.size());
    if (n < nfft) return {};
    const int frames = int(1 + (n - nfft) / hop);
    const double sr = Features::kSampleRate;
    const int binLo = int(std::ceil(30.0 * nfft / sr)), binHi = int(std::floor(8000.0 * nfft / sr));

    signalsmith::linear::RealFFT<float> fft(nfft);
    std::vector<float> win(nfft), frame(nfft);
    for (int i = 0; i < nfft; ++i) win[i] = float(0.5 - 0.5 * std::cos(2 * kPi * i / nfft));   // hanning(n+1)[:-1]
    std::vector<std::complex<float>> X(nfft / 2);
    std::vector<float> prev(binHi - binLo + 1, 0.0f), cur(prev.size());
    std::vector<float> flux(frames, 0.0f);
    for (int t = 0; t < frames; ++t) {
        for (int i = 0; i < nfft; ++i) frame[i] = y[size_t(t) * hop + i] * win[i];
        fft.fft(frame.data(), X.data());
        float sum = 0;
        for (int b = binLo; b <= binHi; ++b) {
            float v = std::log1p(100.0f * std::abs(X[b]));
            cur[b - binLo] = v;
            if (t > 0) sum += std::max(0.0f, v - prev[b - binLo]);
        }
        flux[t] = t > 0 ? sum / float(cur.size()) : 0.0f;
        std::swap(prev, cur);
    }
    // subtract a ~1 s moving average, rectify, normalise to unit std
    const int k = int(std::lround(sr / hop));
    std::vector<double> csum(frames + 1, 0.0);
    for (int i = 0; i < frames; ++i) csum[i + 1] = csum[i] + flux[i];
    std::vector<float> env(frames);
    double mean = 0;
    for (int i = 0; i < frames; ++i) {
        // numpy 'same' convolution window: [i - (k-1)/2 - ..] approximated by a centred window of k
        int a = std::max(0, i - k / 2), b = std::min(frames, i - k / 2 + k);
        double trend = (csum[b] - csum[a]) / k;
        env[i] = std::max(0.0f, float(flux[i] - trend));
        mean += env[i];
    }
    mean /= frames;
    double var = 0;
    for (float v : env) var += (v - mean) * (v - mean);
    float sd = float(std::sqrt(var / frames)) + 1e-9f;
    for (float &v : env) v /= sd;
    return env;
}

}  // namespace

bool computeFeatures(const std::string &pcmPath, int sampleRate, Features &out, std::atomic<float> *progress) {
    FILE *f = fopen(pcmPath.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long bytes = ftell(f);
    fseek(f, 0, SEEK_SET);
    const long frames = bytes / 4;
    std::vector<float> mono(frames);
    {
        std::vector<int16_t> buf(1 << 16);
        long done = 0;
        while (done < frames) {
            long want = std::min<long>(long(buf.size()) / 2, frames - done);
            size_t got = fread(buf.data(), sizeof(int16_t), size_t(want) * 2, f);
            if (got == 0) break;
            for (size_t i = 0; i + 1 < got; i += 2) mono[done++] = (buf[i] + buf[i + 1]) * (0.5f / 32768.0f);
        }
        mono.resize(done);
    }
    fclose(f);
    if (progress) progress->store(0.05f);

    auto t0 = std::chrono::steady_clock::now();
    std::vector<float> y = dsp::resample(mono, sampleRate, Features::kSampleRate);
    std::vector<float>().swap(mono);
    if (progress) progress->store(0.2f);
    auto t1 = std::chrono::steady_clock::now();

    out.env = onsetEnvelope(y);
    out.envFrames = int(out.env.size());
    if (progress) progress->store(0.3f);
    auto t2 = std::chrono::steady_clock::now();

    kernels();   // build once (also off the worker threads)
    out.cqtFrames = int(1 + y.size() / Features::kCqtHop);
    out.cqt.assign(size_t(out.cqtFrames) * Features::kCqtBins, 0.0f);
    const int threads = std::max(1, std::min(4, int(std::thread::hardware_concurrency())));
    std::atomic<int> done{0};
    std::vector<std::thread> pool;
    const int per = (out.cqtFrames + threads - 1) / threads;
    for (int i = 0; i < threads; ++i) {
        int a = i * per, b = std::min(out.cqtFrames, a + per);
        if (a < b) pool.emplace_back(cqtFrames, std::cref(y), a, b, std::ref(out.cqt), &done);
    }
    while (progress) {
        int d = done.load();
        progress->store(0.3f + 0.7f * float(d) / float(out.cqtFrames));
        if (d >= out.cqtFrames) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    for (auto &t : pool) t.join();
    auto t3 = std::chrono::steady_clock::now();
    auto ms = [](auto a, auto b) { return (long long)std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count(); };
    LOGI("features: %ld frames @%d -> resample %lld ms, onsets %lld ms, cqt %lld ms (%d frames, %d threads)",
         frames, sampleRate, ms(t0, t1), ms(t1, t2), ms(t2, t3), out.cqtFrames, threads);
    if (progress) progress->store(1.0f);
    return true;
}

}  // namespace riyaz
