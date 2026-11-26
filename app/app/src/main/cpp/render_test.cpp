// Offline render used only by the test harness: runs the same Stretch settings as live
// playback over part of a decoded song and writes a WAV, so pitch and speed can be measured.
#include <jni.h>

#include <cstdio>
#include <cstdint>
#include <string>
#include <vector>

#include "audio_engine.h"

static void writeWavHeader(FILE *f, int sampleRate, uint32_t frames) {
    auto u32 = [&](uint32_t v) { fwrite(&v, 4, 1, f); };
    auto u16 = [&](uint16_t v) { fwrite(&v, 2, 1, f); };
    fwrite("RIFF", 1, 4, f); u32(36 + frames * 4); fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f); u32(16); u16(1); u16(2); u32(sampleRate); u32(sampleRate * 4); u16(4); u16(16);
    fwrite("data", 1, 4, f); u32(frames * 4);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_riyaz_app_audio_NativeEngine_nativeRenderTest(JNIEnv *env, jobject, jstring jin, jint sampleRate,
                                                       jlong startFrame, jint outFrames, jfloat semis,
                                                       jfloat tempo, jboolean natural, jstring jout) {
    auto str = [&](jstring s) { const char *c = env->GetStringUTFChars(s, nullptr); std::string r(c); env->ReleaseStringUTFChars(s, c); return r; };
    std::string inPath = str(jin), outPath = str(jout);

    FILE *in = fopen(inPath.c_str(), "rb");
    if (!in) return JNI_FALSE;
    fseek(in, 0, SEEK_END);
    int64_t total = ftell(in) / 4;
    std::vector<int16_t> pcm((size_t)total * 2);
    fseek(in, 0, SEEK_SET);
    fread(pcm.data(), 2, pcm.size(), in);
    fclose(in);

    signalsmith::stretch::SignalsmithStretch<float> st;
    st.presetDefault(2, (float)sampleRate);
    riyaz::configureStretch(st, semis, natural, sampleRate);

    const int chunk = 512;
    std::vector<std::vector<float>> ib(2, std::vector<float>(std::max(st.seekLength(), chunk * 4)));
    std::vector<std::vector<float>> ob(2, std::vector<float>(chunk));
    auto fill = [&](int64_t from, int n) {
        for (int i = 0; i < n; ++i) {
            int64_t fr = from + i;
            bool ok = fr >= 0 && fr < total;
            ib[0][i] = ok ? pcm[fr * 2] / 32768.f : 0.f;
            ib[1][i] = ok ? pcm[fr * 2 + 1] / 32768.f : 0.f;
        }
    };
    // Same start-up as a live seek.
    int pre = st.seekLength();
    fill(startFrame - pre, pre);
    st.seek(ib, pre, tempo);

    FILE *out = fopen(outPath.c_str(), "wb");
    if (!out) return JNI_FALSE;
    writeWavHeader(out, sampleRate, (uint32_t)outFrames);
    int64_t pos = startFrame;
    double frac = 0;
    std::vector<int16_t> buf(chunk * 2);
    for (int done = 0; done < outFrames; done += chunk) {
        double need = chunk * (double)tempo + frac;
        int n = (int)need;
        frac = need - n;
        fill(pos, n);
        pos += n;
        st.process(ib, n, ob, chunk);
        int m = std::min(chunk, outFrames - done);
        for (int i = 0; i < m; ++i) {
            for (int c = 0; c < 2; ++c) {
                float v = riyaz::softLimit(ob[c][i]);
                buf[i * 2 + c] = (int16_t)(v * 32767.f);
            }
        }
        fwrite(buf.data(), 2, m * 2, out);
    }
    fclose(out);
    return JNI_TRUE;
}
