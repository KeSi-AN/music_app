#pragma once

#include <atomic>
#include <string>
#include <vector>

namespace riyaz {

// The sung melody of a song, from its vocal track (original - instrumental), as fractional MIDI
// pitch every kHopSec seconds; NaN where nobody sings. Used to show the melody in karaoke, to check
// your singing against it, and (Phase 6) to know the song's vocal range.
struct Melody {
    static constexpr float kHopSec = 0.02f;
    std::vector<float> midi;
};

bool computeMelody(const std::string &originalPcm, const std::string &backingPcm, int sampleRate,
                   Melody &out, std::atomic<float> *progress);

// Cleans raw per-frame pitch (hz, clarity, rms): loudness gate relative to the song, median filter,
// drop blips shorter than minFrames. Exposed for tests.
std::vector<float> cleanMelody(const std::vector<float> &hz, const std::vector<float> &clarity,
                               const std::vector<float> &rms, int minFrames = 4);

}  // namespace riyaz
