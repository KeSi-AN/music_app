#include "transcribe.h"

#include <android/log.h>

#include <cstdio>
#include <cstring>

#include "dsp.h"
#include "whisper.h"

#define LOG_TAG "RiyazWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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

bool transcribe(const TranscribeJob &job, std::vector<HeardSegment> &out, std::atomic<float> *progress,
                std::atomic<bool> *cancel) {
    // the voice alone: original - instrumental (or the original if there's no instrumental), mono
    std::vector<int16_t> a, b;
    if (!readStereo16(job.originalPcm, a)) return false;
    const bool haveBacking = !job.backingPcm.empty() && readStereo16(job.backingPcm, b);
    const size_t frames = haveBacking ? std::min(a.size(), b.size()) / 2 : a.size() / 2;
    std::vector<float> voice(frames);
    for (size_t i = 0; i < frames; ++i) {
        float l = a[2 * i] / 32768.0f, r = a[2 * i + 1] / 32768.0f;
        if (haveBacking) { l -= b[2 * i] / 32768.0f; r -= b[2 * i + 1] / 32768.0f; }
        voice[i] = 0.5f * (l + r);
    }
    std::vector<int16_t>().swap(a);
    std::vector<int16_t>().swap(b);
    std::vector<float> pcm = dsp::resample(voice, job.sampleRate, WHISPER_SAMPLE_RATE);
    std::vector<float>().swap(voice);
    if (progress) progress->store(0.02f);

    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(job.modelPath.c_str(), cp);
    if (!ctx) return false;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = job.threads;
    p.language = job.language.empty() ? "auto" : job.language.c_str();
    p.translate = false;
    // singing makes Whisper loop on its own earlier text; don't feed it back. (This also drops any initial
    // prompt — fine: prompting with the lyrics makes it "hear" them where nobody sings.)
    p.no_context = true;
    p.n_max_text_ctx = 0;
    p.suppress_nst = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    if (!job.vadModelPath.empty()) {
        p.vad = true;
        p.vad_model_path = job.vadModelPath.c_str();
        // singing has few real silences: without a cap, a whole verse becomes one stretch, Whisper writes a
        // line or two for it and the times are useless. ~12 s pieces keep every phrase and its time.
        p.vad_params.max_speech_duration_s = 12.0f;
        p.vad_params.min_silence_duration_ms = 300;
        p.vad_params.speech_pad_ms = 100;
    }
    p.progress_callback = [](whisper_context *, whisper_state *, int pct, void *user) {
        auto *pr = static_cast<std::atomic<float> *>(user);
        if (pr) pr->store(0.02f + 0.98f * pct / 100.0f);
    };
    p.progress_callback_user_data = progress;
    p.abort_callback = [](void *user) { return user && static_cast<std::atomic<bool> *>(user)->load(); };
    p.abort_callback_user_data = cancel;

    const int rc = whisper_full(ctx, p, pcm.data(), int(pcm.size()));
    if (rc == 0) {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            HeardSegment s;
            s.start = whisper_full_get_segment_t0(ctx, i) / 100.0;   // centiseconds
            s.end = whisper_full_get_segment_t1(ctx, i) / 100.0;
            s.text = whisper_full_get_segment_text(ctx, i);
            out.push_back(std::move(s));
        }
        LOGI("transcribed %zu s of audio: %d segments, language %s", pcm.size() / WHISPER_SAMPLE_RATE, n,
             whisper_lang_str(whisper_full_lang_id(ctx)));
    }
    whisper_free(ctx);
    return rc == 0 && !(cancel && cancel->load());
}

}  // namespace riyaz
