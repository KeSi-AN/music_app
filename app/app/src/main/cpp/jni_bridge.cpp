#include <jni.h>

#include <atomic>
#include <memory>
#include <string>

#include "analysis.h"
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

}  // extern "C"
