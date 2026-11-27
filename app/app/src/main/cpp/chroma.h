#pragma once

#include <vector>

namespace riyaz {

// Pitch-class energy ("chroma") of what the microphone hears, for checking which chord is being
// played. 16 kHz mono in; every kHop samples (100 ms) a 4096-point spectrum (256 ms) is folded into
// 12 note bins between 65 Hz and 2.1 kHz.
class ChromaAnalyser {
public:
    static constexpr int kSampleRate = 16000;
    static constexpr int kSize = 4096;
    static constexpr int kHop = 1600;

    struct Result {
        float chroma[12];   // normalised so the largest bin is 1 (all 0 in silence)
        float rms;
    };

    ChromaAnalyser();
    // Adds samples; returns true (and fills r) when a new result is ready. Feed at most kHop at a time.
    bool push(const float *x, int n, Result &r);
    // One spectrum -> chroma, for tests and offline use. x must hold kSize samples.
    void analyse(const float *x, Result &r);

private:
    std::vector<float> buf_, window_, re_, im_;
    std::vector<int> pc_;            // pitch class per FFT bin (-1 = ignored)
    std::vector<float> weight_;      // how close the bin is to the centre of its note
    int fill_ = 0, sinceLast_ = 0;
};

}  // namespace riyaz
