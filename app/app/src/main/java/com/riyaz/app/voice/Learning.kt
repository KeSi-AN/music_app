package com.riyaz.app.voice

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A song's sung melody: fractional MIDI every [hopSec] seconds; NaN where nobody sings. */
class Melody(val midi: FloatArray, val hopSec: Float = 0.02f) {
    fun at(sec: Double): Float {
        val i = (sec / hopSec).roundToInt()
        return if (i in midi.indices) midi[i] else Float.NaN
    }

    private val voiced get() = midi.filter { !it.isNaN() }

    /** The song's vocal range (5th-95th percentile, so brief ornaments don't count) and its centre. */
    fun range(): Triple<Float, Float, Float>? {
        val v = voiced.sorted()
        if (v.size < 50) return null
        return Triple(v[(v.size * 0.05).toInt()], v[(v.size * 0.95).toInt()], v[v.size / 2])
    }

    private val cachedNotes by lazy { computeNotes(0.15f) }

    /** Held notes: runs of voiced frames within half a semitone of their median, at least 0.15 s long. */
    fun notes(): List<MelodyNote> = cachedNotes

    private fun computeNotes(minSec: Float): List<MelodyNote> {
        val out = ArrayList<MelodyNote>()
        val minFrames = (minSec / hopSec).toInt()
        var i = 0
        while (i < midi.size) {
            if (midi[i].isNaN()) { i++; continue }
            var j = i + 1
            while (j < midi.size && !midi[j].isNaN() && abs(midi[j] - median(midi, i, j)) <= 0.5f) j++
            if (j - i >= minFrames) out += MelodyNote(i * hopSec.toDouble(), j * hopSec.toDouble(), median(midi, i, j))
            i = j
        }
        return out
    }

    companion object {
        private fun median(a: FloatArray, from: Int, to: Int): Float {
            val s = a.copyOfRange(from, to).sorted()
            return s[s.size / 2]
        }

        fun file(songDir: File) = File(songDir, "melody.bin")
        fun load(songDir: File): Melody? = runCatching {
            DataInputStream(file(songDir).inputStream().buffered()).use { s ->
                val hop = s.readFloat()
                Melody(FloatArray(s.readInt()) { s.readFloat() }, hop)
            }
        }.getOrNull()
        fun save(songDir: File, m: Melody) =
            DataOutputStream(file(songDir).outputStream().buffered()).use { s ->
                s.writeFloat(m.hopSec); s.writeInt(m.midi.size); m.midi.forEach { s.writeFloat(it) }
            }
    }
}

data class MelodyNote(val start: Double, val end: Double, val midi: Float)

/** One mic reading placed on the song's timeline (seconds in the original recording). */
data class SungFrame(val songSec: Double, val midi: Float)

/** How one melody note went. [target] is in the octave the singer actually used. */
data class NoteResult(val target: Float, val sung: Boolean, val errorCents: Float, val wobble: Float) {
    val inTune get() = sung && abs(errorCents) <= 50f
    val flat get() = sung && errorCents < -50f
    val shaky get() = sung && wobble > 0.6f
    val comfortable get() = inTune && !shaky
    val strained get() = sung && (flat || shaky)
}

data class SessionResult(
    val notes: List<NoteResult>,
    val octave: Int,             // you sang this many octaves away from the recording (e.g. -1)
    val delaySec: Double,        // measured delay between hearing the song and the mic
    val keyShift: Float,
) {
    val sungNotes get() = notes.filter { it.sung }
    val accuracy get() = if (sungNotes.isEmpty()) 0f else sungNotes.count { it.inTune }.toFloat() / sungNotes.size
    val highestComfortable get() = notes.filter { it.comfortable }.maxOfOrNull { it.target }
    val lowestComfortable get() = notes.filter { it.comfortable }.minOfOrNull { it.target }
    val strainedNotes get() = notes.filter { it.strained }.map { it.target.roundToInt() }.distinct().sorted()
    val enough get() = sungNotes.size >= 8
}

/** Compares what you sang with the song's melody (shifted to the key you chose). */
object SessionAnalysis {
    fun analyse(melody: Melody, frames: List<SungFrame>, keyShift: Float): SessionResult {
        // only the stretch of the song you actually sang along to
        val from = (frames.minOfOrNull { it.songSec } ?: 0.0) - 1.0
        val to = (frames.maxOfOrNull { it.songSec } ?: 0.0) + 1.0
        val notes = melody.notes().filter { it.start >= from && it.end <= to }
        if (frames.size < 20 || notes.isEmpty()) return SessionResult(emptyList(), 0, 0.0, keyShift)
        val octave = estimateOctave(melody, frames, keyShift)
        val bestDelay = estimateDelay(melody, frames, keyShift, octave)
        // 3) note by note
        val sorted = frames.sortedBy { it.songSec }
        val results = notes.map { note ->
            val target = note.midi + keyShift + 12 * octave
            val inside = sorted.filter { it.songSec - bestDelay >= note.start && it.songSec - bestDelay < note.end }
            val expected = ((note.end - note.start) / 0.016).coerceAtLeast(1.0)   // mic frames are ~16 ms apart
            val sung = inside.size >= expected * 0.5
            if (!sung) NoteResult(target, false, 0f, 0f) else {
                val errs = inside.map { (it.midi - target) * 100f }.sorted()
                val mean = inside.map { it.midi }.average().toFloat()
                val sd = sqrt(inside.map { (it.midi - mean) * (it.midi - mean) }.average()).toFloat()
                NoteResult(target, true, errs[errs.size / 2], sd)
            }
        }
        return SessionResult(results, octave, bestDelay, keyShift)
    }

    /** Which octave you sing in relative to the recording (e.g. -1 = an octave lower). */
    fun estimateOctave(melody: Melody, frames: List<SungFrame>, keyShift: Float): Int =
        mostCommon(frames.mapNotNull { f ->
            melody.at(f.songSec - 0.15).takeIf { !it.isNaN() }?.let { m -> ((f.midi - (m + keyShift)) / 12f).roundToInt() }
        }) ?: 0

    /**
     * The delay between hearing the song and the mic reading it (output + input latency, Bluetooth adds a
     * lot). Cost per reading: distance from the melody, capped at a semitone, or 1 where the melody is
     * silent; the middle of the cheapest stretch of delays wins (long notes make a plateau).
     */
    fun estimateDelay(melody: Melody, frames: List<SungFrame>, keyShift: Float, octave: Int): Double {
        if (frames.isEmpty()) return 0.15
        val delays = (0..45).map { -0.1 + it * 0.02 }
        val costs = delays.map { d ->
            var c = 0.0
            for (f in frames) {
                val m = melody.at(f.songSec - d)
                c += if (m.isNaN()) 1.0 else minOf(1.0, abs(f.midi - (m + keyShift + 12 * octave)).toDouble())
            }
            c / frames.size
        }
        val minCost = costs.minOrNull()!!
        val near = delays.indices.filter { costs[it] <= minCost + 0.005 }
        return delays[near[near.size / 2]]
    }

    private fun mostCommon(x: List<Int>): Int? = x.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

/** What Riyaz has learned from your singing so far (kept in voice_profile.json under "learned"). */
data class LearnedVoice(
    val comfort: Map<Int, Float> = emptyMap(),   // MIDI note -> decayed count of comfortable notes
    val strain: Map<Int, Float> = emptyMap(),    // MIDI note -> decayed count of strained notes
    val sessions: Int = 0,
    val songs: List<SongPreference> = emptyList(),
) {
    /** Highest / lowest notes with enough comfortable evidence and less strain than comfort. */
    fun learnedHigh(): Int? = comfort.keys.filter { solid(it) }.maxOrNull()
    fun learnedLow(): Int? = comfort.keys.filter { solid(it) }.minOrNull()
    private fun solid(m: Int) = (comfort[m] ?: 0f) >= 1.5f && (strain[m] ?: 0f) < (comfort[m] ?: 0f)

    /** Learns from one session: older evidence fades by 15% so your current voice counts most. */
    fun plus(r: SessionResult, song: SongPreference): LearnedVoice {
        val c = comfort.mapValues { it.value * DECAY }.toMutableMap()
        val s = strain.mapValues { it.value * DECAY }.toMutableMap()
        // at most 3 per note per session, so one long song can't dominate
        r.notes.filter { it.comfortable }.groupBy { it.target.roundToInt() }
            .forEach { (m, l) -> c[m] = (c[m] ?: 0f) + minOf(3, l.size) }
        r.notes.filter { it.strained }.groupBy { it.target.roundToInt() }
            .forEach { (m, l) -> s[m] = (s[m] ?: 0f) + minOf(3, l.size) }
        return LearnedVoice(c.filterValues { it > 0.05f }, s.filterValues { it > 0.05f }, sessions + 1,
            (songs.filter { it.songId != song.songId } + song).takeLast(200))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("comfort", JSONObject(comfort.mapKeys { it.key.toString() }.mapValues { it.value.toDouble() }))
        .put("strain", JSONObject(strain.mapKeys { it.key.toString() }.mapValues { it.value.toDouble() }))
        .put("sessions", sessions)
        .put("songs", JSONArray(songs.map { it.toJson() }))

    companion object {
        const val DECAY = 0.85f
        fun fromJson(j: JSONObject?): LearnedVoice {
            if (j == null) return LearnedVoice()
            fun map(o: JSONObject?) = o?.keys()?.asSequence()?.associate { it.toInt() to o.getDouble(it).toFloat() } ?: emptyMap()
            val songs = j.optJSONArray("songs")?.let { a -> List(a.length()) { SongPreference.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            return LearnedVoice(map(j.optJSONObject("comfort")), map(j.optJSONObject("strain")), j.optInt("sessions"), songs)
        }
    }
}

/** The key you chose for a song and how that sat in your voice: what Phase 6 learns key choices from. */
data class SongPreference(
    val songId: String,
    val title: String,
    val keyShift: Float,
    val octave: Int,
    val sungLow: Float?,      // the song's vocal range in the key and octave you sang it
    val sungHigh: Float?,
    val accuracy: Float,
    val at: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("songId", songId).put("title", title).put("keyShift", keyShift.toDouble())
        .put("octave", octave).put("sungLow", sungLow?.toDouble() ?: JSONObject.NULL).put("sungHigh", sungHigh?.toDouble() ?: JSONObject.NULL)
        .put("accuracy", accuracy.toDouble()).put("at", at)

    companion object {
        fun fromJson(j: JSONObject) = SongPreference(
            j.getString("songId"), j.optString("title"), j.getDouble("keyShift").toFloat(), j.optInt("octave"),
            if (j.isNull("sungLow")) null else j.getDouble("sungLow").toFloat(),
            if (j.isNull("sungHigh")) null else j.getDouble("sungHigh").toFloat(),
            j.optDouble("accuracy").toFloat(), j.optLong("at"),
        )
    }
}
