package com.riyaz.app.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.riyaz.app.audio.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One chord held from [start] to [end] seconds (in the song's original key). */
data class ChordSegment(val start: Double, val end: Double, val chord: Chord)

data class SongAnalysis(
    val bpm: Double,
    val beats: List<Double>,          // beat times in seconds
    val barPhase: Int,                // index (mod 4) of the beats that start a bar
    val key: Key,                     // original key
    val segments: List<ChordSegment>,
) {
    fun chordAt(sec: Double): Int {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val s = segments[mid]
            when {
                sec < s.start -> hi = mid - 1
                sec >= s.end -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("bpm", bpm)
        .put("barPhase", barPhase)
        .put("key", "${MusicTheory.SHARP[key.tonic]}:${if (key.minor) "min" else "maj"}")
        .put("beats", JSONArray(beats.map { (it * 1000).roundToInt() / 1000.0 }))
        .put("chords", JSONArray(segments.map { JSONArray(listOf(it.start, it.end, it.chord.code())) }))

    companion object {
        const val VERSION = 1

        fun fromJson(j: JSONObject): SongAnalysis? {
            if (j.optInt("version") != VERSION) return null
            val k = j.getString("key").split(":")
            val beats = j.getJSONArray("beats").let { a -> List(a.length()) { a.getDouble(it) } }
            val segs = j.getJSONArray("chords").let { a ->
                List(a.length()) { i ->
                    val s = a.getJSONArray(i)
                    ChordSegment(s.getDouble(0), s.getDouble(1), Chord.parse(s.getString(2)))
                }
            }
            return SongAnalysis(
                bpm = j.getDouble("bpm"),
                beats = beats,
                barPhase = j.getInt("barPhase"),
                key = Key(MusicTheory.SHARP.indexOf(k[0]), k.getOrNull(1) == "min"),
                segments = segs,
            )
        }
    }
}

/**
 * Chords, key and beats for a decoded song, entirely on the phone. Port of spike/portable.py:
 *   C++ (resample to 22.05 kHz, constant-Q transform, onset strength)
 *   -> BTC chord transformer (ONNX, MIT licence, 170 chord types) on 108-frame chunks
 *   -> classic tempo + dynamic-programming beat tracker on the onset strength
 *   -> chord probabilities averaged per beat, key from chord durations, bar lines from chord changes.
 */
class SongAnalyzer(private val context: Context) {

    fun analyze(pcmPath: String, sampleRate: Int, onProgress: (Float) -> Unit): SongAnalysis {
        val t0 = System.nanoTime()
        val data = NativeEngine.nativeComputeFeatures(pcmPath, sampleRate)
            ?: error("Couldn't read the song's audio for analysis")
        val cqtFrames = data[0].toInt()
        val envFrames = data[1].toInt()
        val cqt = FloatBuffer.wrap(data, 2, cqtFrames * CQT_BINS).slice()
        val env = FloatArray(envFrames) { data[2 + cqtFrames * CQT_BINS + it] }
        val t1 = System.nanoTime()
        onProgress(0.7f)

        val probs = runBtc(cqt, cqtFrames)
        val t2 = System.nanoTime()
        onProgress(0.9f)

        val fps = SR.toDouble() / ENV_HOP
        val bpm = estimateTempo(env, fps)
        val beats = trackBeats(env, fps, bpm)
        val duration = cqtFrames * CQT_HOP.toDouble() / SR

        // per-beat chord: average the frame probabilities between consecutive beats
        val bounds = buildList {
            add(0.0)
            beats.filter { it > 0.05 && it < duration - 0.05 }.forEach { add(it) }
            add(duration)
        }
        val labels = ArrayList<Chord>(bounds.size)
        val frameSec = CQT_HOP.toDouble() / SR
        val acc = FloatArray(CLASSES)
        for (i in 0 until bounds.size - 1) {
            val a = (bounds[i] / frameSec).roundToInt().coerceIn(0, cqtFrames - 1)
            val b = max(a + 1, (bounds[i + 1] / frameSec).roundToInt().coerceAtMost(cqtFrames))
            acc.fill(0f)
            for (f in a until b) for (c in 0 until CLASSES) acc[c] += probs[f * CLASSES + c]
            var best = 0
            for (c in 1 until CLASSES) if (acc[c] > acc[best]) best = c
            labels += Chord.fromBtc(best)
        }
        val segments = ArrayList<ChordSegment>()
        for (i in labels.indices) {
            val last = segments.lastOrNull()
            if (last != null && last.chord == labels[i]) {
                segments[segments.size - 1] = last.copy(end = bounds[i + 1])
            } else {
                segments += ChordSegment(bounds[i], bounds[i + 1], labels[i])
            }
        }
        val key = MusicTheory.keyFromChords(segments.map { it.chord to (it.end - it.start) })
        // bar phase: beats (between the first and last bound) where chords change most often
        val changes = IntArray(4)
        for (i in 2 until labels.size) if (labels[i] != labels[i - 1]) changes[(i - 1) % 4]++
        val barPhase = changes.indices.maxByOrNull { changes[it] } ?: 0
        val t3 = System.nanoTime()
        Log.i(
            "Riyaz", "analysis: features ${(t1 - t0) / 1_000_000} ms, btc ${(t2 - t1) / 1_000_000} ms, " +
                "beats+chords ${(t3 - t2) / 1_000_000} ms; ${"%.1f".format(bpm)} BPM, key ${key.name()}, ${segments.size} chords",
        )
        onProgress(1f)
        return SongAnalysis(bpm, beats.toList(), barPhase, key, segments)
    }

    // ---------------------------------------------------------------- BTC

    private fun runBtc(cqt: FloatBuffer, frames: Int): FloatArray {
        val env = Ort.env
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val probs = FloatArray(frames * CLASSES)
        env.createSession(bytes, opts).use { session ->
            val chunk = FloatArray(T * CQT_BINS)
            var start = 0
            while (start < frames) {
                val n = min(T, frames - start)
                chunk.fill(0f)
                cqt.position(start * CQT_BINS)
                cqt.get(chunk, 0, n * CQT_BINS)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, T.toLong(), CQT_BINS.toLong())).use { input ->
                    session.run(mapOf("cqt" to input)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = (out[0].value as Array<Array<FloatArray>>)[0]
                        for (t in 0 until n) {
                            val row = logits[t]
                            var m = row[0]
                            for (v in row) if (v > m) m = v
                            var sum = 0.0
                            for (c in 0 until CLASSES) sum += exp((row[c] - m).toDouble())
                            for (c in 0 until CLASSES) probs[(start + t) * CLASSES + c] = (exp((row[c] - m).toDouble()) / sum).toFloat()
                        }
                    }
                }
                start += T
            }
        }
        return probs
    }

    // ---------------------------------------------------------------- tempo + beats (Ellis 2007)

    /** Autocorrelation of the onset strength, weighted by a log-normal prior around 120 BPM. */
    private fun estimateTempo(env: FloatArray, fps: Double): Double {
        val maxLag = (fps * 60 / 30).toInt()
        var bestLag = 1
        var best = -1.0
        for (lag in 1..maxLag) {
            val bpm = 60 * fps / lag
            if (bpm > 220) continue
            var ac = 0.0
            for (i in 0 until env.size - lag) ac += env[i] * env[i + lag]
            val l2 = ln(bpm / 120.0) / ln(2.0)
            val score = ac * exp(-0.5 * l2 * l2)
            if (score > best) { best = score; bestLag = lag }
        }
        return 60 * fps / bestLag
    }

    private fun trackBeats(env: FloatArray, fps: Double, bpm: Double, tightness: Double = 100.0): DoubleArray {
        val period = 60 * fps / bpm
        val n = env.size
        // smooth with a Gaussian of width period/32
        val half = period.toInt()
        val g = DoubleArray(2 * half + 1) { val x = (it - half) * 32.0 / period; exp(-0.5 * x * x) }
        val local = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in g.indices) {
                val j = i + k - half
                if (j in 0 until n) s += env[j] * g[k]
            }
            local[i] = s
        }
        val lo = (-2 * period).roundToInt()
        val hi = (-period / 2).roundToInt()
        val txwt = DoubleArray(hi - lo + 1) { val w = (lo + it).toDouble(); -tightness * ln(-w / period).pow(2) }
        val cum = local.copyOf()
        val back = IntArray(n) { -1 }
        for (i in 0 until n) {
            var bestV = Double.NEGATIVE_INFINITY
            var bestJ = -1
            for (w in lo..hi) {
                val z = i + w
                if (z < 0) continue
                val v = txwt[w - lo] + cum[z]
                if (v > bestV) { bestV = v; bestJ = z }
            }
            if (bestJ >= 0) { cum[i] = local[i] + bestV; back[i] = bestJ }
        }
        // best local maximum of the cumulative score in the last two periods, then trace back
        var end = n - 1
        var endV = Double.NEGATIVE_INFINITY
        for (i in max(1, n - (2 * period).toInt()) until n - 1) {
            if (cum[i] > cum[i - 1] && cum[i] >= cum[i + 1] && cum[i] > endV) { endV = cum[i]; end = i }
        }
        val beats = ArrayList<Int>()
        var i = end
        while (i >= 0) { beats += i; i = back[i] }
        beats.reverse()
        // drop weak beats at the start and end (silence)
        val rms = sqrt(beats.sumOf { local[it] * local[it] } / max(1, beats.size))
        val first = beats.indexOfFirst { local[it] > 0.5 * rms }
        val last = beats.indexOfLast { local[it] > 0.5 * rms }
        val kept = if (first >= 0) beats.subList(first, last + 1) else beats
        return DoubleArray(kept.size) { kept[it] / fps }
    }

    companion object {
        private const val MODEL = "models/btc_large.onnx"
        private const val SR = 22050
        private const val CQT_HOP = 2048
        private const val CQT_BINS = 144
        private const val ENV_HOP = 512
        private const val T = 108
        private const val CLASSES = 170
    }
}

/** analysis.json next to the song's audio. */
object AnalysisStore {
    fun file(songDir: File) = File(songDir, "analysis.json")
    fun load(songDir: File): SongAnalysis? =
        runCatching { SongAnalysis.fromJson(JSONObject(file(songDir).readText())) }.getOrNull()
    fun save(songDir: File, a: SongAnalysis) = file(songDir).writeText(a.toJson().toString())
}
