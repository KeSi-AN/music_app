#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>

#include "pitch.h"

namespace riyaz {

// Microphone -> PitchDetector. Results go into a lock-free ring that the app drains with poll().
class MicInput : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    ~MicInput() override { stop(); }

    bool start();
    void stop();
    bool running() const { return stream_ != nullptr; }

    // Copies results produced since the last poll (oldest first) as [hz, clarity, rms] triples.
    // Returns the number of results copied (at most maxResults).
    int poll(float *out, int maxResults);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int kRing = 512;   // ~8 s of results
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex mutex_;
    PitchDetector detector_;
    PitchDetector::Result ring_[kRing];
    std::atomic<int64_t> written_{0};
    int64_t read_ = 0;
};

}  // namespace riyaz
