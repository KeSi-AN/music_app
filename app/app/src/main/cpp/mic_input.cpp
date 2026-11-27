#include "mic_input.h"

#include <android/log.h>

#include <algorithm>

#define LOG_TAG "RiyazMic"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace riyaz {

bool MicInput::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) return true;
    detector_ = PitchDetector();
    read_ = written_.load();
    chroma_ = ChromaAnalyser();
    chromaRead_ = chromaWritten_.load();
    for (auto preset : {oboe::InputPreset::Unprocessed, oboe::InputPreset::VoiceRecognition}) {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(1)
            ->setSampleRate(PitchDetector::kSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setInputPreset(preset)   // raw microphone: no noise suppression or auto gain bending the pitch
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (b.openStream(stream_) == oboe::Result::OK) {
            if (stream_->requestStart() == oboe::Result::OK) {
                LOGI("mic started: preset %d, rate %d, burst %d", (int)preset, stream_->getSampleRate(), stream_->getFramesPerBurst());
                return true;
            }
            stream_->close();
            stream_.reset();
        }
    }
    LOGE("could not open the microphone");
    return false;
}

void MicInput::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult MicInput::onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) {
    const auto *in = static_cast<const float *>(audioData);
    PitchDetector::Result r;
    // push() may produce at most one result per hop; feed in hop-sized pieces so none are lost
    int done = 0;
    while (done < numFrames) {
        int n = std::min(numFrames - done, PitchDetector::kHop);
        if (detector_.push(in + done, n, r)) {
            int64_t w = written_.load(std::memory_order_relaxed);
            ring_[w % kRing] = r;
            written_.store(w + 1, std::memory_order_release);
        }
        ChromaAnalyser::Result c;
        if (chroma_.push(in + done, n, c)) {
            int64_t w = chromaWritten_.load(std::memory_order_relaxed);
            chromaRing_[w % kChromaRing] = c;
            chromaWritten_.store(w + 1, std::memory_order_release);
        }
        done += n;
    }
    return oboe::DataCallbackResult::Continue;
}

void MicInput::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    LOGI("mic stream closed (%s), restarting", oboe::convertToText(error));
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stream_.reset();
    }
    start();
}

int MicInput::poll(float *out, int maxResults) {
    int64_t w = written_.load(std::memory_order_acquire);
    if (w - read_ > kRing) read_ = w - kRing;   // the app fell behind: skip what was overwritten
    int n = 0;
    while (read_ < w && n < maxResults) {
        const auto &r = ring_[read_ % kRing];
        out[3 * n] = r.hz;
        out[3 * n + 1] = r.clarity;
        out[3 * n + 2] = r.rms;
        ++read_;
        ++n;
    }
    return n;
}

int MicInput::pollChroma(float *out, int maxResults) {
    int64_t w = chromaWritten_.load(std::memory_order_acquire);
    if (w - chromaRead_ > kChromaRing) chromaRead_ = w - kChromaRing;
    int n = 0;
    while (chromaRead_ < w && n < maxResults) {
        const auto &r = chromaRing_[chromaRead_ % kChromaRing];
        std::copy(r.chroma, r.chroma + 12, out + 13 * n);
        out[13 * n + 12] = r.rms;
        ++chromaRead_;
        ++n;
    }
    return n;
}

}  // namespace riyaz
