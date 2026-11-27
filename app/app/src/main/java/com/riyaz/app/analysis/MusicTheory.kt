package com.riyaz.app.analysis

/** Chord qualities in BTC's label order (index = label % 14). */
enum class Quality(val btc: String, val suffix: String, val minor: Boolean) {
    MIN("min", "m", true),
    MAJ("maj", "", false),
    DIM("dim", "dim", true),
    AUG("aug", "aug", false),
    MIN6("min6", "m6", true),
    MAJ6("maj6", "6", false),
    MIN7("min7", "m7", true),
    MINMAJ7("minmaj7", "m(maj7)", true),
    MAJ7("maj7", "maj7", false),
    DOM7("7", "7", false),
    DIM7("dim7", "dim7", true),
    HDIM7("hdim7", "m7b5", true),
    SUS2("sus2", "sus2", false),
    SUS4("sus4", "sus4", false);

    /** The plain triad a beginner would play instead. */
    fun simplified(): Quality = if (minor) MIN else MAJ
}

/** A chord: root pitch class 0..11 (C = 0), or null root for "no chord". */
data class Chord(val root: Int?, val quality: Quality = Quality.MAJ) {
    val isNone get() = root == null

    fun transposed(semitones: Int): Chord =
        if (root == null) this else copy(root = Math.floorMod(root + semitones, 12))

    fun simplified(): Chord = if (root == null) this else copy(quality = quality.simplified())

    fun name(flats: Boolean): String =
        if (root == null) "N.C." else MusicTheory.noteName(root, flats) + quality.suffix

    /** Stable text form used in analysis.json: "A:min7", "N". */
    fun code(): String = if (root == null) "N" else "${MusicTheory.SHARP[root]}:${quality.btc}"

    companion object {
        val NONE = Chord(null)

        fun parse(code: String): Chord {
            if (code == "N" || code == "X") return NONE
            val (r, q) = code.split(":").let { it[0] to it.getOrElse(1) { "maj" } }
            val root = MusicTheory.SHARP.indexOf(r).takeIf { it >= 0 } ?: return NONE
            return Chord(root, Quality.entries.firstOrNull { it.btc == q } ?: Quality.MAJ)
        }

        /** BTC large-vocabulary output index (0..169) to a chord. */
        fun fromBtc(index: Int): Chord =
            if (index >= 168) NONE else Chord(index / 14, Quality.entries[index % 14])
    }
}

data class Key(val tonic: Int, val minor: Boolean) {
    fun transposed(semitones: Int) = copy(tonic = Math.floorMod(tonic + semitones, 12))

    /** Whether chord names in this key are written with flats (F, Bb, Eb … / Dm, Gm, Cm …). */
    val usesFlats: Boolean
        get() = if (minor) tonic in setOf(2, 7, 0, 5, 10, 3) else tonic in setOf(5, 10, 3, 8, 1)

    fun name(): String = MusicTheory.noteName(tonic, usesFlats) + if (minor) " minor" else " major"
    fun shortName(): String = MusicTheory.noteName(tonic, usesFlats) + if (minor) "m" else ""
}

object MusicTheory {
    val SHARP = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    fun noteName(pc: Int, flats: Boolean) = (if (flats) FLAT else SHARP)[Math.floorMod(pc, 12)]

    /**
     * Key from how long each chord is played: score each candidate key by the time spent on its
     * diatonic chords, with a bonus for the tonic chord itself. Matched Chord AI on 3/3 test songs,
     * where a chroma key-profile got 2 wrong.
     */
    fun keyFromChords(segments: List<Pair<Chord, Double>>): Key {
        val time = HashMap<Pair<Int, Boolean>, Double>()
        for ((c, dur) in segments) {
            val r = c.root ?: continue
            val k = r to (c.quality.minor && c.quality != Quality.DIM && c.quality != Quality.HDIM7 && c.quality != Quality.DIM7)
            time[k] = (time[k] ?: 0.0) + dur
        }
        fun t(root: Int, minor: Boolean) = time[Math.floorMod(root, 12) to minor] ?: 0.0
        var best = Key(0, false)
        var bestScore = -1.0
        for (r in 0 until 12) {
            val major = t(r, false) + t(r + 5, false) + t(r + 7, false) + t(r + 9, true) + t(r + 2, true) + t(r + 4, true) +
                0.5 * t(r, false)
            val minor = t(r, true) + t(r + 5, true) + t(r + 7, true) + t(r + 3, false) + t(r + 8, false) + t(r + 10, false) +
                t(r + 7, false) + 0.5 * t(r, true)
            if (major > bestScore) { bestScore = major; best = Key(r, false) }
            if (minor > bestScore) { bestScore = minor; best = Key(r, true) }
        }
        return best
    }
}
