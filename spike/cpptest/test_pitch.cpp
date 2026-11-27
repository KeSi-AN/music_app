// Host test for pitch.cpp: synthetic tones, a harmonic-rich "voice", noise, and streaming via push().
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
#include "pitch.h"
using riyaz::PitchDetector;
static double cents(double a, double b) { return 1200 * std::log2(a / b); }
int main() {
    const int sr = PitchDetector::kSampleRate;
    std::mt19937 rng(1);
    std::normal_distribution<float> noise(0, 1);
    int fails = 0;
    auto check = [&](const char *name, double f0, int harmonics, float noiseAmp) {
        std::vector<float> x(sr);   // 1 s
        for (int i = 0; i < sr; ++i) {
            double v = 0;
            for (int h = 1; h <= harmonics; ++h) v += std::sin(2 * M_PI * f0 * h * i / sr) / h;
            x[i] = float(0.3 * v) + noiseAmp * noise(rng);
        }
        PitchDetector pd;
        PitchDetector::Result r, last;
        int results = 0;
        std::vector<float> recent;
        // stream it in odd-sized blocks, like an audio callback
        for (int i = 0; i < sr; i += 192) {
            int n = std::min(192, sr - i);
            if (pd.push(x.data() + i, n, r)) { last = r; ++results; if (r.hz > 0) recent.push_back(r.hz); }
        }
        // the tuner shows the median of the last ~10 frames (160 ms)
        if (recent.size() >= 10) {
            std::vector<float> t(recent.end() - 10, recent.end());
            std::nth_element(t.begin(), t.begin() + 5, t.end());
            last.hz = t[5];
        }
        bool ok = f0 == 0 ? last.hz == 0 || last.clarity < 0.7f : std::fabs(cents(last.hz, f0)) < 3.0;
        if (!ok) ++fails;
        printf("%-26s f0 %7.2f -> %7.2f Hz (%+6.2f cents) clarity %.2f rms %.3f, %d results/s %s\n", name, f0, last.hz,
               f0 > 0 && last.hz > 0 ? cents(last.hz, f0) : 0.0, last.clarity, last.rms, results, ok ? "ok" : "FAIL");
    };
    check("guitar low E (E2)", 82.41, 1, 0);
    check("guitar A2 harmonic-rich", 110.0, 12, 0);
    check("guitar high E (E4)", 329.63, 6, 0);
    check("A4 440 + noise (SNR ~10dB)", 440.0, 1, 0.07f);
    check("male voice ~A2 sawtooth", 110.0, 20, 0.01f);
    check("female voice C5", 523.25, 8, 0.01f);
    check("soprano high A5", 880.0, 4, 0.0f);
    check("detuned +12 cents", 440.0 * std::pow(2.0, 12 / 1200.0), 3, 0);
    check("noise only", 0, 0, 0.2f);
    printf("%s\n", fails ? "SOME FAILED" : "all ok");
    return fails;
}
