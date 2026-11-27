#pragma once

#include <atomic>
#include <string>
#include <vector>

namespace riyaz {

// Audio features for chord / beat analysis, computed from a decoded song (16-bit stereo PCM file).
// A direct port of spike/portable.py, which was checked against librosa and Chord AI.
struct Features {
    static constexpr int kSampleRate = 22050;
    static constexpr int kCqtBins = 144;       // C1 .. B6, 24 bins per octave (what BTC was trained on)
    static constexpr int kCqtHop = 2048;
    static constexpr int kEnvHop = 512;

    int cqtFrames = 0;
    std::vector<float> cqt;   // cqtFrames x 144, frame-major, already BTC-normalised: (ln(|C|+1e-6) - mean) / std
    int envFrames = 0;
    std::vector<float> env;   // onset strength, kSampleRate / kEnvHop frames per second
};

// progress (0..1) is updated as work proceeds; may be null.
bool computeFeatures(const std::string &pcmPath, int sampleRate, Features &out, std::atomic<float> *progress);

}  // namespace riyaz
