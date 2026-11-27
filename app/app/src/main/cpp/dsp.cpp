#include "dsp.h"

#include <algorithm>
#include <cmath>

namespace riyaz::dsp {
namespace {
constexpr double kPi = 3.14159265358979323846;

double besselI0(double x) {
    double sum = 1, term = 1;
    for (int k = 1; k < 50; ++k) {
        term *= (x / (2 * k)) * (x / (2 * k));
        sum += term;
        if (term < 1e-12 * sum) break;
    }
    return sum;
}

// Kaiser-windowed sinc, any ratio. Filter taps are tabulated at kPhases fractional offsets.
}  // namespace

std::vector<float> resample(const std::vector<float> &in, int srIn, int srOut) {
    if (srIn == srOut) return in;
    constexpr int kHalf = 16, kTaps = 2 * kHalf, kPhases = 512;
    const double ratio = double(srOut) / srIn;
    const double cutoff = std::min(1.0, ratio) * 0.945;
    const double beta = 8.6, i0beta = besselI0(beta);

    std::vector<float> table((kPhases + 1) * kTaps);
    for (int p = 0; p <= kPhases; ++p) {
        double frac = double(p) / kPhases;
        for (int t = 0; t < kTaps; ++t) {
            int k = t - kHalf + 1;          // -15 .. 16
            double d = k - frac;
            double u = d / kHalf;
            double w = (std::abs(u) < 1) ? besselI0(beta * std::sqrt(1 - u * u)) / i0beta : 0;
            double x = cutoff * d;
            double sinc = (std::abs(x) < 1e-12) ? 1 : std::sin(kPi * x) / (kPi * x);
            table[p * kTaps + t] = float(cutoff * sinc * w);
        }
    }

    const size_t nOut = size_t(in.size() * ratio);
    std::vector<float> out(nOut);
    const long n = long(in.size());
    for (size_t i = 0; i < nOut; ++i) {
        double pos = i / ratio;
        long base = long(std::floor(pos));
        double fp = (pos - base) * kPhases;
        int p = int(fp);
        float a = float(fp - p);
        const float *r0 = &table[p * kTaps], *r1 = &table[(p + 1) * kTaps];
        float acc = 0;
        for (int t = 0; t < kTaps; ++t) {
            long idx = base + t - kHalf + 1;
            if (idx < 0 || idx >= n) continue;
            acc += (r0[t] + a * (r1[t] - r0[t])) * in[idx];
        }
        out[i] = acc;
    }
    return out;
}


}  // namespace riyaz::dsp
