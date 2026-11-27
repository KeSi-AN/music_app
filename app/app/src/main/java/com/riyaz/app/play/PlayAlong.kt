package com.riyaz.app.play

import com.riyaz.app.analysis.Chord
import com.riyaz.app.analysis.ChordSegment
import com.riyaz.app.analysis.Quality
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Which chord the microphone hears, from its 12 note energies (chroma). Each note of a chord brings its
 * harmonics with it (an octave, an octave and a fifth, two octaves, a major third above that…), so the
 * templates include the first six, each half as strong as the one before.
 *
 * Tested in spike/chroma_variants.py: guitar strummed along 3 songs → 100% of the right chords accepted,
 * 0% of chords a fourth away; on the full recordings (vocals and all) 63–86% of the right chords.
 */
object ChordMatch {
    private val HARMONICS = intArrayOf(0, 12, 19, 24, 28, 31)
    private const val DECAY = 0.5f

    private val INTERVALS = mapOf(
        Quality.MAJ to intArrayOf(0, 4, 7), Quality.MIN to intArrayOf(0, 3, 7), Quality.DIM to intArrayOf(0, 3, 6),
        Quality.AUG to intArrayOf(0, 4, 8), Quality.DOM7 to intArrayOf(0, 4, 7, 10), Quality.MAJ7 to intArrayOf(0, 4, 7, 11),
        Quality.MIN7 to intArrayOf(0, 3, 7, 10), Quality.MINMAJ7 to intArrayOf(0, 3, 7, 11), Quality.MAJ6 to intArrayOf(0, 4, 7, 9),
        Quality.MIN6 to intArrayOf(0, 3, 7, 9), Quality.DIM7 to intArrayOf(0, 3, 6, 9), Quality.HDIM7 to intArrayOf(0, 3, 6, 10),
        Quality.SUS2 to intArrayOf(0, 2, 7), Quality.SUS4 to intArrayOf(0, 5, 7),
    )

    fun notes(c: Chord): Set<Int> {
        val r = c.root ?: return emptySet()
        return (INTERVALS[c.quality] ?: intArrayOf(0, 4, 7)).map { Math.floorMod(r + it, 12) }.toSet()
    }

    fun template(c: Chord): FloatArray {
        val t = FloatArray(12)
        val root = c.root ?: return t
        val played = (INTERVALS[c.quality] ?: intArrayOf(0, 4, 7)).toList() + 0   // the root is usually doubled
        for (i in played) for ((h, s) in HARMONICS.withIndex()) t[Math.floorMod(root + i + s, 12)] += DECAY.pow(h)
        return t
    }

    private val TRIADS: List<Pair<Chord, FloatArray>> =
        (0 until 12).flatMap { r -> listOf(Chord(r, Quality.MAJ), Chord(r, Quality.MIN)) }.map { it to template(it) }

    fun score(chroma: FloatArray, t: FloatArray): Float {
        var dot = 0f; var a = 0f; var b = 0f
        for (i in 0 until 12) { dot += chroma[i] * t[i]; a += chroma[i] * chroma[i]; b += t[i] * t[i] }
        return if (a <= 0f || b <= 0f) 0f else dot / sqrt(a * b)
    }

    /** The major or minor chord the chroma sounds most like. */
    fun best(chroma: FloatArray): Chord = TRIADS.maxByOrNull { (_, t) -> score(chroma, t) }!!.first

    /**
     * Whether [chroma] fits [expected]: the triad heard is its plain version (G for G7 or Gsus4), or is made
     * only of its notes (C heard for Am7 = A C E G).
     */
    fun matches(chroma: FloatArray, expected: Chord): Boolean {
        if (expected.isNone) return true
        val b = best(chroma)
        return b == expected.simplified() || notes(expected).containsAll(notes(b))
    }
}

/** One chord of the song and how you did on it. [heard] = loud readings while it sounded. */
data class ChordResult(val start: Double, val end: Double, val chord: Chord, val heard: Int, val ok: Boolean, val sounded: Chord?) {
    val played get() = heard >= 2
}

data class PlayAlongResult(val chords: List<ChordResult>) {
    val played get() = chords.filter { it.played }
    val right get() = played.count { it.ok }
    val percent get() = if (played.isEmpty()) 0 else right * 100 / played.size
    val enough get() = played.size >= 4

    /** The chords you most often got wrong, with what they usually sounded like instead. */
    fun trouble(): List<Pair<Chord, Chord?>> = played.filter { !it.ok }.groupBy { it.chord }
        .entries.sortedByDescending { it.value.size }.take(3)
        .map { (c, l) -> c to l.mapNotNull { it.sounded }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
}

/**
 * Follows you strumming along with a song. Each mic reading (10 a second, covering the last 256 ms) is
 * placed on the song's timeline; only readings that lie wholly inside one chord and are loud enough to
 * be you playing count. Each chord is judged on the average of its readings; the live view uses the
 * last few readings.
 */
class PlayAlongSession(segments: List<ChordSegment>, private val keyShift: Int) {
    private val segs = segments.filter { !it.chord.isNone && it.end - it.start >= 0.6 }
    private val sums = Array(segs.size) { FloatArray(12) }
    private val counts = IntArray(segs.size)
    private val recent = ArrayDeque<FloatArray>()
    private var recentSeg = -1
    private var loud = 0f

    /** The sounding chord at [songSec] (the key you're hearing the song in), or null between chords. */
    fun expectedAt(songSec: Double): Chord? = index(songSec).takeIf { it >= 0 }?.let { segs[it].chord.transposed(keyShift) }

    private fun index(t: Double): Int {
        var lo = 0; var hi = segs.size - 1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            when {
                t < segs[m].start -> hi = m - 1
                t >= segs[m].end -> lo = m + 1
                else -> return m
            }
        }
        return -1
    }

    /**
     * Adds a reading whose window ends at [windowEndSec] on the song's timeline. Returns null when it
     * doesn't count, else (matches the chord, the chord it sounds like) from the last few readings.
     */
    fun add(windowEndSec: Double, chroma: FloatArray, rms: Float): Pair<Boolean, Chord>? {
        loud = if (rms > loud) rms else loud * 0.995f + rms * 0.005f
        if (rms < 0.003f || rms < 0.2f * loud) return null
        val i = index(windowEndSec - WINDOW / 2)
        if (i < 0) return null
        val s = segs[i]
        if (windowEndSec - WINDOW < s.start + 0.05 || windowEndSec > s.end + 0.02) return null
        for (k in 0 until 12) sums[i][k] += chroma[k]
        counts[i]++
        if (recentSeg != i) { recent.clear(); recentSeg = i }
        recent.addLast(chroma.copyOf()); if (recent.size > 3) recent.removeFirst()
        val avg = FloatArray(12).also { a -> recent.forEach { c -> for (k in 0 until 12) a[k] += c[k] } }
        val expected = s.chord.transposed(keyShift)
        return ChordMatch.matches(avg, expected) to ChordMatch.best(avg)
    }

    fun result(): PlayAlongResult = PlayAlongResult(segs.indices.map { i ->
        val expected = segs[i].chord.transposed(keyShift)
        if (counts[i] == 0) ChordResult(segs[i].start, segs[i].end, expected, 0, false, null)
        else ChordResult(segs[i].start, segs[i].end, expected, counts[i], ChordMatch.matches(sums[i], expected), ChordMatch.best(sums[i]))
    })

    companion object {
        const val WINDOW = 0.256   // seconds of sound in each reading (4096 samples at 16 kHz)
    }
}
