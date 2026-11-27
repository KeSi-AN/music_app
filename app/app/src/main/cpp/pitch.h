#pragma once

#include <cstdint>
#include <vector>

namespace riyaz {

// YIN pitch detector (de Cheveigné & Kawahara 2002) for one voice or one string.
// Feed mono samples at kSampleRate; every kHop samples it analyses the last kWindow samples.
class PitchDetector {
public:
    static constexpr int kSampleRate = 16000;
    static constexpr int kWindow = 1024;          // 64 ms
    static constexpr int kHop = 256;              // 62.5 results per second
    static constexpr float kMinHz = 60.0f;        // below a bass voice / guitar drop-D
    static constexpr float kMaxHz = 1200.0f;      // above a soprano's top notes

    struct Result {
        float hz = 0;        // 0 = no clear pitch
        float clarity = 0;   // 1 - YIN aperiodicity: 1 = pure tone, < 0.7 = noise / unclear
        float rms = 0;       // input level (0..1)
    };

    PitchDetector();
    // Returns true when a new result is ready (every kHop samples).
    bool push(const float *samples, int n, Result &out);
    // Analyses exactly kWindow + maxLag samples (for tests).
    Result analyse(const float *buf);

private:
    int maxLag_, minLag_;
    std::vector<float> buf_;     // last kWindow + maxLag samples
    int fill_ = 0, sinceLast_ = 0;
    std::vector<float> d_, cmnd_;
};

}  // namespace riyaz
