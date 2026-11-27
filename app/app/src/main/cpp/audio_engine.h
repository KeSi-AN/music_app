#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace riyaz {

// Key/formant settings shared by live playback and the offline render test.
void configureStretch(signalsmith::stretch::SignalsmithStretch<float> &st, float semis, bool natural,
                      int sampleRate);

// Output safety: modern masters sit at 0 dBFS and pitch-shifting overshoots that slightly.
// A small fixed trim plus a smooth soft-clip keeps peaks under full scale without audible pumping.
inline float softLimit(float x) {
    constexpr float kTrim = 0.89f;   // -1 dB
    constexpr float kKnee = 0.80f;
    x *= kTrim;
    float a = x < 0 ? -x : x;
    if (a <= kKnee) return x;
    float y = kKnee + (1.0f - kKnee) * std::tanh((a - kKnee) / (1.0f - kKnee));
    return x < 0 ? -y : y;
}

// Plays a decoded song (raw 16-bit stereo PCM file) with independent key and speed.
//
// Threads:
//  - worker thread: reads PCM, runs Signalsmith Stretch, writes into a small ring buffer.
//  - Oboe callback thread: copies from the ring buffer to the speaker. Never blocks.
//  - caller (JNI/UI) thread: sets atomics only.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine() = default;
    ~AudioEngine() override;

    bool load(const std::string &pcmPath, int sampleRate);
    void unload();

    // Karaoke: an instrumental (backing track) of the same song, same format and length.
    // Output = backing + vocalGain * (original - backing): 0 = karaoke, 1 = the original recording.
    bool loadBacking(const std::string &pcmPath);
    void unloadBacking();
    void setVocalGain(float g) { vocalGain_ = g < 0 ? 0 : (g > 1 ? 1 : g); }
    bool hasBacking() const { return backing_.load() != nullptr; }

    bool play();
    void pause();
    void seek(int64_t frame);

    void setSemitones(float semitones);   // key shift, may be fractional (cents)
    void setTempo(float tempo);           // 1.0 = original speed
    void setNaturalVoice(bool on);        // formant compensation

    int64_t position() const { return posReport_.load(); }
    int64_t frames() const { return frames_; }
    int sampleRate() const { return sampleRate_; }
    bool isPlaying() const { return playing_.load(); }
    bool reachedEnd() const { return ended_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int kChannels = 2;
    static constexpr int kRingFrames = 4096;   // ~90 ms: keeps key/speed changes responsive
    static constexpr int kChunkFrames = 512;   // output frames produced per worker step
    static constexpr float kMaxTempo = 2.0f;

    bool openStream();
    void closeStream();
    void startWorker();
    void stopWorker();

    void workerLoop();
    void applyParams();
    void doSeek(int64_t target);
    void produce(int outFrames);
    void readInput(int64_t start, int count);
    void updatePosition();

    // Source PCM (memory-mapped, interleaved stereo int16)
    const int16_t *pcm_ = nullptr;
    size_t mapLen_ = 0;
    std::atomic<const int16_t *> backing_{nullptr};
    size_t backingLen_ = 0;
    std::atomic<float> vocalGain_{1.0f};
    int64_t frames_ = 0;
    int sampleRate_ = 44100;

    // Output stream
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamMutex_;

    // DSP state, worker thread only
    signalsmith::stretch::SignalsmithStretch<float> stretch_;
    std::vector<std::vector<float>> inBuf_, outBuf_;
    int64_t inPos_ = 0;       // next source frame to feed
    double inFrac_ = 0;       // fractional input carry for non-integer tempo
    float appliedSemis_ = 1e9f;
    bool appliedNatural_ = false;
    bool feedingDone_ = false;
    int64_t lastLoggedPos_ = 0;

    // Ring buffer: worker writes, callback reads. Totals are monotonic frame counters.
    std::vector<float> ring_ = std::vector<float>(kRingFrames * kChannels, 0.0f);
    std::atomic<int64_t> writeTotal_{0};
    std::atomic<int64_t> readTotal_{0};
    std::atomic<int64_t> discardUntil_{0};   // callback skips anything written before a seek

    // Controls
    std::atomic<float> semis_{0.0f};
    std::atomic<float> tempo_{1.0f};
    std::atomic<bool> natural_{false};
    std::atomic<bool> playing_{false};
    std::atomic<bool> ended_{false};
    std::atomic<bool> quit_{false};
    std::atomic<int64_t> seekReq_{-1};
    std::atomic<int64_t> posReport_{0};

    std::thread worker_;
};

}  // namespace riyaz
