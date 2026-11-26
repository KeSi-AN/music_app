#include "audio_engine.h"

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#define LOG_TAG "RiyazEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace riyaz {

AudioEngine::~AudioEngine() { unload(); }

bool AudioEngine::load(const std::string &pcmPath, int sampleRate) {
    unload();

    int fd = open(pcmPath.c_str(), O_RDONLY);
    if (fd < 0) {
        LOGE("open failed: %s", pcmPath.c_str());
        return false;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < (off_t)(kChannels * sizeof(int16_t))) {
        close(fd);
        LOGE("empty or unreadable pcm");
        return false;
    }
    void *map = mmap(nullptr, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);  // the mapping stays valid
    if (map == MAP_FAILED) {
        LOGE("mmap failed");
        return false;
    }
    madvise(map, (size_t)st.st_size, MADV_SEQUENTIAL);

    pcm_ = static_cast<const int16_t *>(map);
    mapLen_ = (size_t)st.st_size;
    frames_ = (int64_t)(mapLen_ / (kChannels * sizeof(int16_t)));
    sampleRate_ = sampleRate;

    stretch_.presetDefault(kChannels, (float)sampleRate_);
    int inCap = std::max(stretch_.seekLength(), (int)(kChunkFrames * kMaxTempo) + 2);
    inBuf_.assign(kChannels, std::vector<float>(inCap, 0.0f));
    outBuf_.assign(kChannels, std::vector<float>(kChunkFrames, 0.0f));
    appliedSemis_ = 1e9f;  // force re-apply

    writeTotal_ = 0;
    readTotal_ = 0;
    discardUntil_ = 0;
    ended_ = false;
    playing_ = false;
    seekReq_ = 0;  // start from the beginning (also primes the stretcher)

    if (!openStream()) {
        unload();
        return false;
    }
    startWorker();
    LOGI("loaded %lld frames @ %d Hz", (long long)frames_, sampleRate_);
    return true;
}

void AudioEngine::unload() {
    playing_ = false;
    stopWorker();
    closeStream();
    if (pcm_) {
        munmap((void *)pcm_, mapLen_);
        pcm_ = nullptr;
        mapLen_ = 0;
    }
    frames_ = 0;
    posReport_ = 0;
}

// ---------------------------------------------------------------- stream

bool AudioEngine::openStream() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(kChannels)
        ->setSampleRate(sampleRate_)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGE("openStream: %s", oboe::convertToText(r));
        stream_.reset();
        return false;
    }
    // A little headroom over the minimum avoids glitches without hurting responsiveness.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 4);
    if (playing_) stream_->requestStart();
    return true;
}

void AudioEngine::closeStream() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    // Typically the output device changed (headphones, Bluetooth). Reopen on the new device.
    LOGI("stream closed (%s), reopening", oboe::convertToText(error));
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        stream_.reset();
    }
    if (pcm_) openStream();
}

bool AudioEngine::play() {
    if (!pcm_) return false;
    if (ended_) {
        ended_ = false;
        seek(0);
    }
    playing_ = true;
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_) return false;
    return stream_->requestStart() == oboe::Result::OK;
}

void AudioEngine::pause() {
    playing_ = false;
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) stream_->requestPause();
}

void AudioEngine::seek(int64_t frame) {
    frame = std::clamp<int64_t>(frame, 0, std::max<int64_t>(frames_ - 1, 0));
    ended_ = false;
    posReport_ = frame;  // UI sees the new position immediately
    seekReq_ = frame;
}

void AudioEngine::setSemitones(float s) { semis_ = std::clamp(s, -12.0f, 12.0f); }
void AudioEngine::setTempo(float t) { tempo_ = std::clamp(t, 0.25f, kMaxTempo); }
void AudioEngine::setNaturalVoice(bool on) { natural_ = on; }

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *, void *audioData,
                                                   int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    if (!playing_.load(std::memory_order_relaxed)) {
        std::memset(out, 0, sizeof(float) * numFrames * kChannels);
        return oboe::DataCallbackResult::Continue;
    }
    int64_t w = writeTotal_.load(std::memory_order_acquire);
    int64_t r = readTotal_.load(std::memory_order_relaxed);
    int64_t d = discardUntil_.load(std::memory_order_acquire);
    if (r < d) r = std::min(d, w);

    int n = (int)std::min<int64_t>(w - r, numFrames);
    for (int i = 0; i < n; ++i) {
        size_t slot = (size_t)((r + i) % kRingFrames) * kChannels;
        out[i * kChannels] = ring_[slot];
        out[i * kChannels + 1] = ring_[slot + 1];
    }
    if (n < numFrames) {  // underrun: silence rather than garbage
        std::memset(out + n * kChannels, 0, sizeof(float) * (numFrames - n) * kChannels);
    }
    readTotal_.store(r + n, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}

// ---------------------------------------------------------------- worker

void AudioEngine::startWorker() {
    quit_ = false;
    worker_ = std::thread([this] { workerLoop(); });
}

void AudioEngine::stopWorker() {
    quit_ = true;
    if (worker_.joinable()) worker_.join();
}

void AudioEngine::workerLoop() {
    using namespace std::chrono_literals;
    while (!quit_) {
        int64_t req = seekReq_.exchange(-1);
        if (req >= 0) doSeek(req);
        applyParams();

        int64_t w = writeTotal_.load(std::memory_order_relaxed);
        int64_t r = std::max(readTotal_.load(std::memory_order_acquire),
                             discardUntil_.load(std::memory_order_relaxed));
        int64_t space = kRingFrames - (w - r);
        if (space >= kChunkFrames) {
            produce(kChunkFrames);
        } else {
            std::this_thread::sleep_for(2ms);
        }
        updatePosition();
    }
}

void AudioEngine::applyParams() {
    float s = semis_.load();
    bool nat = natural_.load();
    if (s != appliedSemis_ || nat != appliedNatural_) {
        stretch_.setTransposeSemitones(s, 8000.0f / (float)sampleRate_);
        if (nat) {
            stretch_.setFormantSemitones(0, true);  // keep voice formants where they were
        } else {
            stretch_.setFormantFactor(1, false);
        }
        appliedSemis_ = s;
        appliedNatural_ = nat;
    }
}

void AudioEngine::doSeek(int64_t target) {
    stretch_.reset();
    int pre = stretch_.seekLength();
    readInput(target - pre, pre);  // pre-roll so the first output is already "warm"
    stretch_.seek(inBuf_, pre, tempo_.load());
    inPos_ = target;
    inFrac_ = 0;
    feedingDone_ = false;
    discardUntil_.store(writeTotal_.load(std::memory_order_relaxed), std::memory_order_release);
    posReport_ = target;
}

void AudioEngine::readInput(int64_t start, int count) {
    const float scale = 1.0f / 32768.0f;
    for (int i = 0; i < count; ++i) {
        int64_t f = start + i;
        if (f >= 0 && f < frames_) {
            inBuf_[0][i] = pcm_[f * kChannels] * scale;
            inBuf_[1][i] = pcm_[f * kChannels + 1] * scale;
        } else {
            inBuf_[0][i] = 0.0f;
            inBuf_[1][i] = 0.0f;
        }
    }
}

void AudioEngine::produce(int outFrames) {
    float tempo = tempo_.load();
    double need = outFrames * (double)tempo + inFrac_;
    int inCount = (int)need;
    inFrac_ = need - inCount;

    readInput(inPos_, inCount);
    inPos_ += inCount;
    stretch_.process(inBuf_, inCount, outBuf_, outFrames);

    int64_t w = writeTotal_.load(std::memory_order_relaxed);
    for (int i = 0; i < outFrames; ++i) {
        size_t slot = (size_t)((w + i) % kRingFrames) * kChannels;
        ring_[slot] = outBuf_[0][i];
        ring_[slot + 1] = outBuf_[1][i];
    }
    writeTotal_.store(w + outFrames, std::memory_order_release);
}

void AudioEngine::updatePosition() {
    if (seekReq_.load() >= 0) return;  // a seek is pending; keep reporting its target
    float tempo = tempo_.load();
    int64_t w = writeTotal_.load(std::memory_order_relaxed);
    int64_t r = std::max(readTotal_.load(std::memory_order_acquire),
                         discardUntil_.load(std::memory_order_relaxed));
    double buffered = (double)(w - r) + stretch_.outputLatency();
    int64_t pos = inPos_ - stretch_.inputLatency() - (int64_t)(buffered * tempo);
    pos = std::clamp<int64_t>(pos, 0, frames_);
    posReport_ = pos;

    if (pos >= frames_ && playing_ && !ended_) {
        ended_ = true;
        pause();
    }
}

}  // namespace riyaz
