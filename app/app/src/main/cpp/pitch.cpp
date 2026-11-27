#include "pitch.h"

#include <algorithm>
#include <cmath>

namespace riyaz {

PitchDetector::PitchDetector() {
    maxLag_ = int(std::ceil(kSampleRate / kMinHz));
    minLag_ = int(std::floor(kSampleRate / kMaxHz));
    buf_.assign(kWindow + maxLag_ + 2, 0.0f);
    d_.assign(maxLag_ + 2, 0.0f);
    cmnd_.assign(maxLag_ + 2, 1.0f);
}

bool PitchDetector::push(const float *samples, int n, Result &out) {
    // buf_ holds the newest samples at its end; it is shifted once per hop, not per sample
    bool ready = false;
    const int size = int(buf_.size());
    for (int i = 0; i < n; ++i) {
        if (fill_ < size) {
            buf_[fill_++] = samples[i];
            if (fill_ < size) continue;
            sinceLast_ = kHop;          // first full window: analyse now
        } else {
            buf_[size - kHop + sinceLast_] = samples[i];
            ++sinceLast_;
        }
        if (sinceLast_ >= kHop) {
            out = analyse(buf_.data());
            ready = true;
            // make room for the next hop
            std::move(buf_.begin() + kHop, buf_.end(), buf_.begin());
            sinceLast_ = 0;
        }
    }
    return ready;
}

PitchDetector::Result PitchDetector::analyse(const float *x) {
    Result r;
    double energy = 0;
    for (int j = 0; j < kWindow; ++j) energy += double(x[j]) * x[j];
    r.rms = float(std::sqrt(energy / kWindow));
    if (r.rms < 1e-4f) return r;   // silence

    // difference function d(tau) and its cumulative-mean-normalised form
    d_[0] = 0;
    cmnd_[0] = 1;
    double running = 0;
    for (int tau = 1; tau <= maxLag_; ++tau) {
        double s = 0;
        const float *a = x, *b = x + tau;
        for (int j = 0; j < kWindow; ++j) {
            float diff = a[j] - b[j];
            s += diff * diff;
        }
        d_[tau] = float(s);
        running += s;
        cmnd_[tau] = running > 0 ? float(s * tau / running) : 1.0f;
    }

    // first dip below the threshold (then walk to its bottom); otherwise the global minimum
    constexpr float kThreshold = 0.12f;
    int tau = -1;
    for (int t = std::max(2, minLag_); t < maxLag_; ++t) {
        if (cmnd_[t] < kThreshold) {
            while (t + 1 < maxLag_ && cmnd_[t + 1] < cmnd_[t]) ++t;
            tau = t;
            break;
        }
    }
    if (tau < 0) {
        tau = int(std::min_element(cmnd_.begin() + std::max(2, minLag_), cmnd_.begin() + maxLag_) - cmnd_.begin());
        if (cmnd_[tau] > 0.35f) {           // nothing periodic enough
            r.clarity = 1.0f - cmnd_[tau];
            return r;
        }
    }
    // Octave errors: if a half, third or quarter of the period fits nearly as well, it's the real pitch
    // (common when the fundamental is weak, e.g. low notes through a phone speaker or mic).
    for (int k = 4; k >= 2; --k) {
        int t0 = int(std::lround(float(tau) / k));
        if (t0 < std::max(2, minLag_)) continue;
        int best = t0;
        for (int t = std::max(2, t0 - 2); t <= std::min(maxLag_ - 1, t0 + 2); ++t)
            if (cmnd_[t] < cmnd_[best]) best = t;
        if (cmnd_[best] < std::max(0.2f, cmnd_[tau] * 1.5f)) {
            while (best + 1 < maxLag_ && cmnd_[best + 1] < cmnd_[best]) ++best;
            while (best - 1 > std::max(2, minLag_) && cmnd_[best - 1] < cmnd_[best]) --best;
            tau = best;
            break;
        }
    }
    // parabolic interpolation around the minimum for sub-sample precision
    float betterTau = float(tau);
    if (tau > 1 && tau < maxLag_) {
        // on the raw difference function: the normalised one is biased at short lags (high notes)
        float s0 = d_[tau - 1], s1 = d_[tau], s2 = d_[tau + 1];
        float denom = s0 + s2 - 2 * s1;
        if (std::fabs(denom) > 1e-9f) betterTau = tau + 0.5f * (s0 - s2) / denom;
    }
    r.hz = kSampleRate / betterTau;
    r.clarity = 1.0f - cmnd_[tau];
    return r;
}

}  // namespace riyaz
