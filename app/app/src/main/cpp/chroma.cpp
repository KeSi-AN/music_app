#include "chroma.h"

#include <algorithm>
#include <cmath>

namespace riyaz {
namespace {

// in-place iterative radix-2 complex FFT
void fft(std::vector<float> &re, std::vector<float> &im) {
    const int n = int(re.size());
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
    }
    for (int len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * M_PI / len;
        const float wr = float(std::cos(ang)), wi = float(std::sin(ang));
        for (int i = 0; i < n; i += len) {
            float cr = 1.0f, ci = 0.0f;
            for (int k = 0; k < len / 2; ++k) {
                const int a = i + k, b = a + len / 2;
                const float tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                re[b] = re[a] - tr; im[b] = im[a] - ti;
                re[a] += tr; im[a] += ti;
                const float ncr = cr * wr - ci * wi;
                ci = cr * wi + ci * wr;
                cr = ncr;
            }
        }
    }
}

}  // namespace

ChromaAnalyser::ChromaAnalyser()
    : buf_(kSize, 0.0f), window_(kSize), re_(kSize), im_(kSize), pc_(kSize / 2, -1), weight_(kSize / 2, 0.0f) {
    for (int i = 0; i < kSize; ++i) window_[i] = 0.5f - 0.5f * std::cos(2.0 * M_PI * i / (kSize - 1));
    for (int k = 1; k < kSize / 2; ++k) {
        const double f = double(k) * kSampleRate / kSize;
        if (f < 65.0 || f > 2100.0) continue;
        const double midi = 69.0 + 12.0 * std::log2(f / 440.0);
        const double nearest = std::round(midi);
        pc_[k] = (int(nearest) % 12 + 12) % 12;
        weight_[k] = float(std::max(0.0, 1.0 - 2.0 * std::fabs(midi - nearest)));   // 1 at the note, 0 half-way
    }
}

void ChromaAnalyser::analyse(const float *x, Result &r) {
    double sq = 0.0;
    for (int i = 0; i < kSize; ++i) { re_[i] = x[i] * window_[i]; im_[i] = 0.0f; sq += double(x[i]) * x[i]; }
    r.rms = float(std::sqrt(sq / kSize));
    fft(re_, im_);
    double c[12] = {0};
    for (int k = 1; k < kSize / 2; ++k) {
        if (pc_[k] < 0) continue;
        // plain magnitude: tried log and square-root compression too (spike/chroma_variants.py); compression
        // lifts each string's harmonics to nearly the level of the notes played and blurs major vs minor
        const double mag = std::sqrt(double(re_[k]) * re_[k] + double(im_[k]) * im_[k]);
        c[pc_[k]] += weight_[k] * mag;
    }
    double mx = 0.0, mn = 1e30;
    for (double v : c) { mx = std::max(mx, v); mn = std::min(mn, v); }
    for (int i = 0; i < 12; ++i) r.chroma[i] = mx > 1e-9 ? float((c[i] - mn) / (mx - mn + 1e-9)) : 0.0f;
}

bool ChromaAnalyser::push(const float *x, int n, Result &r) {
    // keep the last kSize samples
    if (n >= kSize) { std::copy(x + n - kSize, x + n, buf_.begin()); fill_ = kSize; }
    else {
        std::move(buf_.begin() + n, buf_.end(), buf_.begin());
        std::copy(x, x + n, buf_.end() - n);
        fill_ = std::min(kSize, fill_ + n);
    }
    sinceLast_ += n;
    if (fill_ < kSize || sinceLast_ < kHop) return false;
    sinceLast_ -= kHop;
    analyse(buf_.data(), r);
    return true;
}

}  // namespace riyaz
