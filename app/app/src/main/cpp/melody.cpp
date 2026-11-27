#include "melody.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>
#include <thread>

#include "dsp.h"
#include "pitch.h"

namespace riyaz {
namespace {

bool readStereo16(const std::string &path, std::vector<int16_t> &out) {
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long bytes = ftell(f);
    fseek(f, 0, SEEK_SET);
    out.resize(size_t(bytes / 2));
    size_t got = fread(out.data(), sizeof(int16_t), out.size(), f);
    fclose(f);
    out.resize(got);
    return got > 0;
}

}  // namespace

std::vector<float> cleanMelody(const std::vector<float> &hz, const std::vector<float> &clarity,
                               const std::vector<float> &rms, int minFrames) {
    const size_t n = hz.size();
    const float nan = std::numeric_limits<float>::quiet_NaN();
    // loudness gate: the vocal track has instrument bleed and reverb tails in "silent" parts
    std::vector<float> loud;
    for (size_t i = 0; i < n; ++i) if (hz[i] > 0 && clarity[i] >= 0.6f) loud.push_back(rms[i]);
    float gate = 0.0f;
    if (!loud.empty()) {
        std::nth_element(loud.begin(), loud.begin() + loud.size() * 9 / 10, loud.end());
        gate = 0.12f * loud[loud.size() * 9 / 10];
    }
    std::vector<float> m(n, nan);
    for (size_t i = 0; i < n; ++i)
        if (hz[i] > 0 && clarity[i] >= 0.6f && rms[i] >= gate) m[i] = 69.0f + 12.0f * std::log2(hz[i] / 440.0f);
    // median of 5 over voiced neighbours: removes single-frame octave slips
    std::vector<float> med(n, nan);
    for (size_t i = 0; i < n; ++i) {
        if (std::isnan(m[i])) continue;
        float w[5];
        int k = 0;
        for (int d = -2; d <= 2; ++d) {
            long j = long(i) + d;
            if (j >= 0 && j < long(n) && !std::isnan(m[j])) w[k++] = m[j];
        }
        std::sort(w, w + k);
        med[i] = w[k / 2];
    }
    // octave slips: nobody leaps an octave and straight back. Compare each frame with the median of its
    // surrounding ~1.6 s; ~an octave away means the tracker locked onto a harmonic (or sub-harmonic).
    // Checked against librosa pYIN on a real vocal track: octave errors 5.8% -> 0%.
    for (int round = 0; round < 2; ++round) {
        std::vector<float> out = med;
        std::vector<float> w;
        for (size_t i = 0; i < n; ++i) {
            if (std::isnan(med[i])) continue;
            w.clear();
            for (long j = std::max(0L, long(i) - 40); j <= std::min(long(n) - 1, long(i) + 40); ++j)
                if (!std::isnan(med[j])) w.push_back(med[j]);
            std::nth_element(w.begin(), w.begin() + w.size() / 2, w.end());
            float local = w[w.size() / 2];
            if (med[i] - local > 9.0f) out[i] = med[i] - 12.0f;
            else if (local - med[i] > 9.0f) out[i] = med[i] + 12.0f;
        }
        med.swap(out);
    }
    // drop blips shorter than minFrames
    size_t i = 0;
    while (i < n) {
        if (std::isnan(med[i])) { ++i; continue; }
        size_t j = i;
        while (j < n && !std::isnan(med[j])) ++j;
        if (long(j - i) < minFrames) std::fill(med.begin() + long(i), med.begin() + long(j), nan);
        i = j;
    }
    return med;
}

bool computeMelody(const std::string &originalPcm, const std::string &backingPcm, int sampleRate,
                   Melody &out, std::atomic<float> *progress) {
    std::vector<int16_t> a, b;
    if (!readStereo16(originalPcm, a) || !readStereo16(backingPcm, b)) return false;
    const size_t frames = std::min(a.size(), b.size()) / 2;
    std::vector<float> voice(frames);
    for (size_t i = 0; i < frames; ++i) {
        float l = (a[2 * i] - b[2 * i]) / 32768.0f;
        float r = (a[2 * i + 1] - b[2 * i + 1]) / 32768.0f;
        voice[i] = 0.5f * (l + r);
    }
    std::vector<int16_t>().swap(a);
    std::vector<int16_t>().swap(b);
    if (progress) progress->store(0.1f);
    std::vector<float> y = dsp::resample(voice, sampleRate, PitchDetector::kSampleRate);
    std::vector<float>().swap(voice);
    if (progress) progress->store(0.3f);

    const int hop = int(Melody::kHopSec * PitchDetector::kSampleRate);   // 320 samples
    const int need = PitchDetector::kWindow + int(std::ceil(PitchDetector::kSampleRate / PitchDetector::kMinHz)) + 2;
    if (long(y.size()) < need) return false;
    const int count = int((y.size() - need) / hop) + 1;
    std::vector<float> hz(count), clarity(count), rms(count);
    std::atomic<int> done{0};
    const int threads = std::max(1, std::min(4, int(std::thread::hardware_concurrency())));
    std::vector<std::thread> pool;
    for (int t = 0; t < threads; ++t) {
        pool.emplace_back([&, t]() {
            PitchDetector pd;
            for (int k = t; k < count; k += threads) {
                PitchDetector::Result r = pd.analyse(y.data() + size_t(k) * hop);
                hz[k] = r.hz;
                clarity[k] = r.clarity;
                rms[k] = r.rms;
                int d = done.fetch_add(1) + 1;
                if (progress && (d & 255) == 0) progress->store(0.3f + 0.65f * d / count);
            }
        });
    }
    for (auto &th : pool) th.join();
    // frame k covers samples [k*hop, k*hop + window): its centre is window/2 later than its start
    out.midi = cleanMelody(hz, clarity, rms);
    const int shift = int(std::lround((PitchDetector::kWindow / 2.0) / hop));
    out.midi.insert(out.midi.begin(), shift, std::numeric_limits<float>::quiet_NaN());
    if (progress) progress->store(1.0f);
    return true;
}

}  // namespace riyaz
