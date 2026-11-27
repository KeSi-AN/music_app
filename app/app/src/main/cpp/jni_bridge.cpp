#include <jni.h>

#include <atomic>
#include <memory>
#include <string>

#include "analysis.h"
#include "separation.h"
#include "mic_input.h"
#include "melody.h"
#include "transcribe.h"
#include "audio_engine.h"

// One engine for the whole app; NativeEngine.kt is a Kotlin object wrapping these calls.
static std::unique_ptr<riyaz::AudioEngine> gEngine;

static riyaz::AudioEngine &engine() {
    if (!gEngine) gEngine = std::make_unique<riyaz::AudioEngine>();
    return *gEngine;
}

#define FN(name) Java_com_riyaz_app_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jboolean JNICALL FN(nativeLoad)(JNIEnv *env, jobject, jstring path, jint sampleRate) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::string s(p);
    env->ReleaseStringUTFChars(path, p);
    return engine().load(s, sampleRate) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(nativeUnload)(JNIEnv *, jobject) { engine().unload(); }

JNIEXPORT jboolean JNICALL FN(nativeLoadBacking)(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::string s(p);
    env->ReleaseStringUTFChars(path, p);
    return engine().loadBacking(s) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL FN(nativeUnloadBacking)(JNIEnv *, jobject) { engine().unloadBacking(); }
JNIEXPORT void JNICALL FN(nativeSetVocalGain)(JNIEnv *, jobject, jfloat g) { engine().setVocalGain(g); }
JNIEXPORT jboolean JNICALL FN(nativePlay)(JNIEnv *, jobject) { return engine().play() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT void JNICALL FN(nativePause)(JNIEnv *, jobject) { engine().pause(); }
JNIEXPORT void JNICALL FN(nativeSeek)(JNIEnv *, jobject, jlong frame) { engine().seek(frame); }
JNIEXPORT void JNICALL FN(nativeSetSemitones)(JNIEnv *, jobject, jfloat s) { engine().setSemitones(s); }
JNIEXPORT void JNICALL FN(nativeSetTempo)(JNIEnv *, jobject, jfloat t) { engine().setTempo(t); }
JNIEXPORT void JNICALL FN(nativeSetNaturalVoice)(JNIEnv *, jobject, jboolean on) { engine().setNaturalVoice(on); }
JNIEXPORT jlong JNICALL FN(nativePosition)(JNIEnv *, jobject) { return engine().position(); }
JNIEXPORT jlong JNICALL FN(nativeFrames)(JNIEnv *, jobject) { return engine().frames(); }
JNIEXPORT jboolean JNICALL FN(nativeIsPlaying)(JNIEnv *, jobject) { return engine().isPlaying() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jboolean JNICALL FN(nativeReachedEnd)(JNIEnv *, jobject) { return engine().reachedEnd() ? JNI_TRUE : JNI_FALSE; }

// ---- song analysis (features only; the models run in Kotlin via ONNX Runtime)

static std::atomic<float> gAnalysisProgress{0.0f};

// Returns [cqtFrames, envFrames, cqt (cqtFrames*144), env (envFrames)], or null on failure.
JNIEXPORT jfloatArray JNICALL FN(nativeComputeFeatures)(JNIEnv *env, jobject, jstring path, jint sampleRate) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::string s(p);
    env->ReleaseStringUTFChars(path, p);
    gAnalysisProgress = 0.0f;
    riyaz::Features f;
    if (!riyaz::computeFeatures(s, sampleRate, f, &gAnalysisProgress)) return nullptr;
    const jsize n = jsize(2 + f.cqt.size() + f.env.size());
    jfloatArray arr = env->NewFloatArray(n);
    if (!arr) return nullptr;
    float head[2] = {float(f.cqtFrames), float(f.envFrames)};
    env->SetFloatArrayRegion(arr, 0, 2, head);
    env->SetFloatArrayRegion(arr, 2, jsize(f.cqt.size()), f.cqt.data());
    env->SetFloatArrayRegion(arr, jsize(2 + f.cqt.size()), jsize(f.env.size()), f.env.data());
    return arr;
}

JNIEXPORT jfloat JNICALL FN(nativeAnalysisProgress)(JNIEnv *, jobject) { return gAnalysisProgress.load(); }

// ---- vocal separation (MDX-Net). Signal processing here; the model runs in Kotlin.

JNIEXPORT jlong JNICALL FN(nativeMdxOpen)(JNIEnv *env, jobject, jstring path, jint songRate, jint nFft, jint dimF) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::string s(p);
    env->ReleaseStringUTFChars(path, p);
    auto *job = new riyaz::MdxJob();
    if (!job->open(s, songRate, nFft, dimF)) { delete job; return 0; }
    return reinterpret_cast<jlong>(job);
}
JNIEXPORT jint JNICALL FN(nativeMdxChunks)(JNIEnv *, jobject, jlong h) {
    return reinterpret_cast<riyaz::MdxJob *>(h)->chunks();
}
JNIEXPORT void JNICALL FN(nativeMdxInput)(JNIEnv *env, jobject, jlong h, jint k, jfloatArray out) {
    auto *job = reinterpret_cast<riyaz::MdxJob *>(h);
    jfloat *p = env->GetFloatArrayElements(out, nullptr);
    job->input(k, p);
    env->ReleaseFloatArrayElements(out, p, 0);
}
JNIEXPORT void JNICALL FN(nativeMdxOutput)(JNIEnv *env, jobject, jlong h, jint k, jfloatArray pred) {
    auto *job = reinterpret_cast<riyaz::MdxJob *>(h);
    jfloat *p = env->GetFloatArrayElements(pred, nullptr);
    job->output(k, p);
    env->ReleaseFloatArrayElements(pred, p, JNI_ABORT);
}
JNIEXPORT jboolean JNICALL FN(nativeMdxWriteBacking)(JNIEnv *env, jobject, jlong h, jstring path, jfloat compensate) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::string s(p);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<riyaz::MdxJob *>(h)->writeBacking(s, compensate) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL FN(nativeMdxClose)(JNIEnv *, jobject, jlong h) {
    delete reinterpret_cast<riyaz::MdxJob *>(h);
}

// ---- microphone pitch (tuner, voice range test)

static riyaz::MicInput gMic;

JNIEXPORT jboolean JNICALL FN(nativeMicStart)(JNIEnv *, jobject) { return gMic.start() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT void JNICALL FN(nativeMicStop)(JNIEnv *, jobject) { gMic.stop(); }
// Fills out with [hz, clarity, rms] triples produced since the last call; returns how many.
JNIEXPORT jint JNICALL FN(nativeMicPoll)(JNIEnv *env, jobject, jfloatArray out) {
    jsize len = env->GetArrayLength(out);
    jfloat *p = env->GetFloatArrayElements(out, nullptr);
    int n = gMic.poll(p, len / 3);
    env->ReleaseFloatArrayElements(out, p, 0);
    return n;
}

JNIEXPORT jint JNICALL FN(nativeMicPollChroma)(JNIEnv *env, jobject, jfloatArray out) {
    jsize len = env->GetArrayLength(out);
    jfloat *p = env->GetFloatArrayElements(out, nullptr);
    int n = gMic.pollChroma(p, len / 13);
    env->ReleaseFloatArrayElements(out, p, 0);
    return n;
}

// ---- song melody from the vocal track (original - instrumental)

static std::atomic<float> gMelodyProgress{0.0f};

// Returns fractional MIDI pitch every 20 ms (NaN = nobody singing), or null.
JNIEXPORT jfloatArray JNICALL FN(nativeComputeMelody)(JNIEnv *env, jobject, jstring orig, jstring backing, jint sampleRate) {
    auto str = [&](jstring s) { const char *c = env->GetStringUTFChars(s, nullptr); std::string r(c); env->ReleaseStringUTFChars(s, c); return r; };
    gMelodyProgress = 0.0f;
    riyaz::Melody m;
    if (!riyaz::computeMelody(str(orig), str(backing), sampleRate, m, &gMelodyProgress)) return nullptr;
    jfloatArray arr = env->NewFloatArray(jsize(m.midi.size()));
    if (arr) env->SetFloatArrayRegion(arr, 0, jsize(m.midi.size()), m.midi.data());
    return arr;
}
JNIEXPORT jfloat JNICALL FN(nativeMelodyProgress)(JNIEnv *, jobject) { return gMelodyProgress.load(); }

// ---- Whisper (lyrics from the vocal track)
static std::atomic<float> gTranscribeProgress{0.0f};
static std::atomic<bool> gTranscribeCancel{false};

// Returns [start, end, text, start, end, text, …] as strings, or null on failure / cancel.
JNIEXPORT jobjectArray JNICALL FN(nativeTranscribe)(JNIEnv *env, jobject, jstring model, jstring vad, jstring orig, jstring backing,
                                                    jint sampleRate, jstring language, jint threads) {
    auto str = [&](jstring s) {
        if (!s) return std::string();
        const char *c = env->GetStringUTFChars(s, nullptr); std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
    };
    riyaz::TranscribeJob job;
    job.modelPath = str(model); job.vadModelPath = str(vad); job.originalPcm = str(orig); job.backingPcm = str(backing);
    job.sampleRate = sampleRate; job.language = str(language); job.threads = threads;
    gTranscribeProgress = 0.0f;
    gTranscribeCancel = false;
    std::vector<riyaz::HeardSegment> segs;
    if (!riyaz::transcribe(job, segs, &gTranscribeProgress, &gTranscribeCancel)) return nullptr;
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(jsize(segs.size() * 3), strCls, nullptr);
    for (size_t i = 0; i < segs.size(); ++i) {
        char buf[32];
        snprintf(buf, sizeof buf, "%.2f", segs[i].start);
        env->SetObjectArrayElement(arr, jsize(3 * i), env->NewStringUTF(buf));
        snprintf(buf, sizeof buf, "%.2f", segs[i].end);
        env->SetObjectArrayElement(arr, jsize(3 * i + 1), env->NewStringUTF(buf));
        // NewStringUTF wants modified UTF-8; Whisper can cut a character in half at a segment edge, so drop broken bytes
        std::string t; const std::string &x = segs[i].text;
        for (size_t k = 0; k < x.size();) {
            unsigned char c = x[k]; int n = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 0;
            bool ok = n > 0 && k + n <= x.size();
            for (int q = 1; ok && q < n; ++q) ok = (static_cast<unsigned char>(x[k + q]) & 0xC0) == 0x80;
            if (ok && n < 4) t.append(x, k, n);
            k += ok ? n : 1;
        }
        env->SetObjectArrayElement(arr, jsize(3 * i + 2), env->NewStringUTF(t.c_str()));
    }
    return arr;
}
JNIEXPORT jfloat JNICALL FN(nativeTranscribeProgress)(JNIEnv *, jobject) { return gTranscribeProgress.load(); }
JNIEXPORT void JNICALL FN(nativeTranscribeCancel)(JNIEnv *, jobject) { gTranscribeCancel = true; }

}  // extern "C"
