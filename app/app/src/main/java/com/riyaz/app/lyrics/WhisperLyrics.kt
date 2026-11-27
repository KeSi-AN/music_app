package com.riyaz.app.lyrics

import android.content.Context
import android.util.Log
import com.riyaz.app.audio.NativeEngine
import com.riyaz.app.net.ModelFile
import org.json.JSONArray
import java.io.File

/**
 * Whisper (whisper.cpp, on the phone) listening to a song's vocal track: puts times on lyrics that
 * came without them, or writes out a draft when no lyrics were found.
 *
 * Model choice (spike, 2026-11-28, Uska Hi Bana vocals): base looped and mixed scripts; small with voice
 * detection and no carried-over context gave clean, recognisable Hindi lines with sensible times.
 */
class WhisperLyrics(context: Context) {
    private val dir = File(context.filesDir, "models")

    val ready get() = MODEL.ready(dir) && VAD.ready(dir)
    val downloadBytes get() = (if (MODEL.ready(dir)) 0 else MODEL.bytes) + (if (VAD.ready(dir)) 0 else VAD.bytes)

    fun ensureModels(onProgress: (Float) -> Unit, isCancelled: () -> Boolean) {
        val total = (MODEL.bytes + VAD.bytes).toFloat()
        VAD.ensure(dir, { onProgress(it / total) }, isCancelled)
        MODEL.ensure(dir, { onProgress((VAD.bytes + it) / total) }, isCancelled)
    }

    /** What was sung, with times (text as Whisper wrote it). Null on failure or cancel. */
    fun hear(originalPcm: File, backingPcm: File?, sampleRate: Int, language: String?): List<TimedText>? {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val t0 = System.currentTimeMillis()
        val out = NativeEngine.nativeTranscribe(MODEL.file(dir).absolutePath, VAD.file(dir).absolutePath, originalPcm.absolutePath,
            backingPcm?.takeIf { it.exists() }?.absolutePath, sampleRate, language, threads) ?: return null
        Log.i("Riyaz", "whisper: ${out.size / 3} segments in ${System.currentTimeMillis() - t0} ms ($threads threads, language $language)")
        return (0 until out.size / 3).mapNotNull { i ->
            val text = out[3 * i + 2].trim()
            if (text.isEmpty() || text.all { !it.isLetter() }) null
            else TimedText(out[3 * i].toDouble(), out[3 * i + 1].toDouble(), text)
        }
    }

    companion object {
        val MODEL = ModelFile("ggml-small-q5_1.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            190_085_487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb")
        val VAD = ModelFile("ggml-silero-v5.1.2.bin", "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
            885_098L, "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf")

        fun cancel() = NativeEngine.nativeTranscribeCancel()
        fun progress() = NativeEngine.nativeTranscribeProgress()

        /** Whisper's language code for the languages Riyaz detects; null lets Whisper decide. */
        fun code(language: String?): String? = when (language) {
            "Hindi" -> "hi"; "Telugu" -> "te"; "Bengali" -> "bn"; "English" -> "en"; "Tamil" -> "ta"; "Punjabi" -> "pa"
            "Urdu" -> "ur"; "Kannada" -> "kn"; "Malayalam" -> "ml"; "Marathi" -> "mr"; "Gujarati" -> "gu"
            else -> null
        }

        /** heard.json in the song folder: what Whisper heard, so syncing again doesn't mean listening again. */
        fun file(songDir: File) = File(songDir, "heard.json")
        fun save(songDir: File, heard: List<TimedText>) =
            file(songDir).writeText(JSONArray(heard.map { JSONArray(listOf(it.start, it.end, it.text)) }).toString())
        fun load(songDir: File): List<TimedText>? = runCatching {
            val a = JSONArray(file(songDir).readText())
            List(a.length()) { i -> a.getJSONArray(i).let { TimedText(it.getDouble(0), it.getDouble(1), it.getString(2)) } }
        }.getOrNull()
    }
}
