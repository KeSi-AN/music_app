package com.riyaz.app.voice

import com.riyaz.app.audio.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** One pitch reading from the microphone. [midi] is fractional (69.0 = A4), null when unvoiced. */
data class PitchFrame(val hz: Float, val clarity: Float, val rms: Float, val tMs: Long) {
    val voiced get() = hz > 0 && clarity >= 0.8f && rms >= 0.003f
    fun midi(a4: Float = 440f): Float? = if (voiced) Notes.midi(hz, a4) else null
}

object Notes {
    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun midi(hz: Float, a4: Float = 440f): Float = 69f + 12f * log2(hz / a4)
    fun hz(midi: Float, a4: Float = 440f): Float = a4 * 2f.pow((midi - 69f) / 12f)

    /** "A4", "C#3" */
    fun name(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)
    fun name(midi: Float): String = name(midi.roundToInt())

    /** Cents away from the nearest note (-50..+50). */
    fun cents(midi: Float): Float = (midi - midi.roundToInt()) * 100f
}

/** Polls the native pitch detector. Start/stop it with the screen that uses it. */
class PitchTracker {
    private val buf = FloatArray(3 * 128)
    private val t0 = System.nanoTime()

    fun start(): Boolean = NativeEngine.nativeMicStart()
    fun stop() = NativeEngine.nativeMicStop()

    /** Frames produced since the last call (about 62 per second). */
    fun poll(): List<PitchFrame> {
        val n = NativeEngine.nativeMicPoll(buf)
        val now = (System.nanoTime() - t0) / 1_000_000
        // results are 16 ms apart; date them backwards from now
        return List(n) { i -> PitchFrame(buf[3 * i], buf[3 * i + 1], buf[3 * i + 2], now - (n - 1 - i) * 16L) }
    }
}

/**
 * The singer's range in MIDI note numbers (60 = middle C). Comfortable = notes held steadily;
 * extremes = the furthest reached at all. Phase 5 will refine this from karaoke sessions.
 */
data class VoiceProfile(
    val comfortableLow: Float,
    val comfortableHigh: Float,
    val extremeLow: Float,
    val extremeHigh: Float,
    val center: Float,          // the note you naturally pick to hold
    val testedAt: Long,
) {
    val octaves get() = (comfortableHigh - comfortableLow) / 12f

    /** Nearest classical voice type by comfortable range (a rough guide, not a verdict). */
    fun voiceType(): String {
        val types = listOf(
            "bass" to (40f to 64f), "baritone" to (45f to 67f), "tenor" to (48f to 72f),
            "alto" to (53f to 77f), "mezzo-soprano" to (57f to 81f), "soprano" to (60f to 84f),
        )
        return types.minByOrNull { (_, r) -> abs(r.first - comfortableLow) + abs(r.second - comfortableHigh) }!!.first
    }

    fun toJson(): JSONObject = JSONObject()
        .put("comfortableLow", comfortableLow.toDouble()).put("comfortableHigh", comfortableHigh.toDouble())
        .put("extremeLow", extremeLow.toDouble()).put("extremeHigh", extremeHigh.toDouble())
        .put("center", center.toDouble()).put("testedAt", testedAt)

    companion object {
        fun fromJson(j: JSONObject) = VoiceProfile(
            j.getDouble("comfortableLow").toFloat(), j.getDouble("comfortableHigh").toFloat(),
            j.getDouble("extremeLow").toFloat(), j.getDouble("extremeHigh").toFloat(),
            j.getDouble("center").toFloat(), j.getLong("testedAt"),
        )
    }
}

/** voice_profile.json: the current profile plus the history of range tests. */
class VoiceStore(dir: File) {
    private val file = File(dir, "voice_profile.json")

    fun load(): VoiceProfile? = runCatching { VoiceProfile.fromJson(JSONObject(file.readText()).getJSONObject("current")) }.getOrNull()

    fun save(p: VoiceProfile) {
        val old = runCatching { JSONObject(file.readText()) }.getOrNull()
        val history = old?.optJSONArray("history") ?: JSONArray()
        history.put(p.toJson())
        file.writeText(JSONObject().put("current", p.toJson()).put("history", history).toString(2))
    }
}

/** Turns the three recorded steps of the range test into a [VoiceProfile]. */
object RangeAnalysis {
    /** A held note: pitch stays within +-0.6 semitones for at least [minMs]. Returns their median pitches. */
    fun heldNotes(midis: List<Float>, frameMs: Int = 16, minMs: Int = 300): List<Float> {
        val clean = medianFilter(midis, 5)
        val out = ArrayList<Float>()
        val need = minMs / frameMs
        var start = 0
        for (i in 1..clean.size) {
            val run = clean.subList(start, i)
            val med = run.sorted()[run.size / 2]
            val broke = i == clean.size || abs(clean[i] - med) > 0.6f
            if (broke) {
                if (i - start >= need) out += med
                start = i
            }
        }
        return out
    }

    fun medianFilter(x: List<Float>, k: Int): List<Float> {
        if (x.size < k) return x
        val h = k / 2
        return List(x.size) { i -> x.subList(maxOf(0, i - h), minOf(x.size, i + h + 1)).sorted().let { it[it.size / 2] } }
    }

    fun percentile(x: List<Float>, p: Float): Float = x.sorted().let { it[((it.size - 1) * p).roundToInt().coerceIn(0, it.size - 1)] }

    /**
     * [hold], [down], [up]: voiced MIDI pitches from each step. Returns null with a reason if the
     * recording isn't usable.
     */
    fun analyse(hold: List<Float>, down: List<Float>, up: List<Float>, now: Long): Pair<VoiceProfile?, String?> {
        if (hold.size < 60) return null to "We didn't hear a steady note in the first step. Try again a little louder."
        if (down.size < 60 || up.size < 60) return null to "We didn't hear enough singing while sliding. Try again, a little louder or closer to the phone."
        val center = medianFilter(hold, 5).sorted().let { it[it.size / 2] }
        val downHeld = heldNotes(down)
        val upHeld = heldNotes(up)
        val low = (downHeld.minOrNull() ?: percentile(down, 0.1f)).coerceAtMost(center)
        val high = (upHeld.maxOrNull() ?: percentile(up, 0.9f)).coerceAtLeast(center)
        val exLow = minOf(low, percentile(medianFilter(down, 5), 0.02f))
        val exHigh = maxOf(high, percentile(medianFilter(up, 5), 0.98f))
        if (high - low < 3f) return null to "Your low and high notes came out almost the same. Slide further down and up, holding the last note."
        return VoiceProfile(low, high, exLow, exHigh, center, now) to null
    }
}
