#pragma once

#include <atomic>
#include <string>
#include <vector>

namespace riyaz {

// Whisper on a song's vocal track (whisper.cpp, on the phone): what was sung, with times.
struct TranscribeJob {
    std::string modelPath;
    std::string vadModelPath;   // Silero VAD: skips the instrumental parts (empty = off)
    std::string originalPcm;    // 16-bit stereo PCM
    std::string backingPcm;     // the instrumental (vocals = original - instrumental); empty = use the original
    int sampleRate = 48000;
    std::string language;       // "hi", "te", "bn", "en" … or empty for automatic
    int threads = 4;
};

struct HeardSegment {
    double start, end;          // seconds
    std::string text;
};

bool transcribe(const TranscribeJob &job, std::vector<HeardSegment> &out, std::atomic<float> *progress,
                std::atomic<bool> *cancel);

}  // namespace riyaz
