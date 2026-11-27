package com.riyaz.app.analysis

/**
 * Guitar fingering for a chord in standard tuning. [frets] is low E to high e; -1 = muted, 0 = open.
 * [baseFret] is the fret shown at the top of the diagram (1 for open-position chords).
 */
data class Fingering(val frets: IntArray, val baseFret: Int, val barre: Int? = null)

object GuitarChords {
    // Open-position shapes players actually use (root pitch class -> quality -> frets).
    private val OPEN: Map<Pair<Int, Quality>, IntArray> = buildMap {
        fun put(root: Int, q: Quality, vararg f: Int) = put(root to q, f)
        val C = 0; val D = 2; val E = 4; val F = 5; val G = 7; val A = 9; val B = 11
        put(C, Quality.MAJ, -1, 3, 2, 0, 1, 0)
        put(C, Quality.DOM7, -1, 3, 2, 3, 1, 0)
        put(C, Quality.MAJ7, -1, 3, 2, 0, 0, 0)
        put(C, Quality.SUS2, -1, 3, 0, 0, 1, 3)
        put(C, Quality.SUS4, -1, 3, 3, 0, 1, 1)
        put(C, Quality.MAJ6, -1, 3, 2, 2, 1, 0)
        put(D, Quality.MAJ, -1, -1, 0, 2, 3, 2)
        put(D, Quality.MIN, -1, -1, 0, 2, 3, 1)
        put(D, Quality.DOM7, -1, -1, 0, 2, 1, 2)
        put(D, Quality.MIN7, -1, -1, 0, 2, 1, 1)
        put(D, Quality.MAJ7, -1, -1, 0, 2, 2, 2)
        put(D, Quality.SUS2, -1, -1, 0, 2, 3, 0)
        put(D, Quality.SUS4, -1, -1, 0, 2, 3, 3)
        put(D, Quality.MAJ6, -1, -1, 0, 2, 0, 2)
        put(E, Quality.MAJ, 0, 2, 2, 1, 0, 0)
        put(E, Quality.MIN, 0, 2, 2, 0, 0, 0)
        put(E, Quality.DOM7, 0, 2, 0, 1, 0, 0)
        put(E, Quality.MIN7, 0, 2, 0, 0, 0, 0)
        put(E, Quality.MAJ7, 0, 2, 1, 1, 0, 0)
        put(E, Quality.SUS4, 0, 2, 2, 2, 0, 0)
        put(E, Quality.SUS2, 0, 2, 4, 4, 0, 0)
        put(F, Quality.MAJ7, -1, -1, 3, 2, 1, 0)
        put(G, Quality.MAJ, 3, 2, 0, 0, 0, 3)
        put(G, Quality.DOM7, 3, 2, 0, 0, 0, 1)
        put(G, Quality.MAJ7, 3, 2, 0, 0, 0, 2)
        put(G, Quality.SUS4, 3, 3, 0, 0, 1, 3)
        put(G, Quality.SUS2, 3, 0, 0, 0, 3, 3)
        put(G, Quality.MAJ6, 3, 2, 0, 0, 0, 0)
        put(A, Quality.MAJ, -1, 0, 2, 2, 2, 0)
        put(A, Quality.MIN, -1, 0, 2, 2, 1, 0)
        put(A, Quality.DOM7, -1, 0, 2, 0, 2, 0)
        put(A, Quality.MIN7, -1, 0, 2, 0, 1, 0)
        put(A, Quality.MAJ7, -1, 0, 2, 1, 2, 0)
        put(A, Quality.SUS2, -1, 0, 2, 2, 0, 0)
        put(A, Quality.SUS4, -1, 0, 2, 2, 3, 0)
        put(A, Quality.MAJ6, -1, 0, 2, 2, 2, 2)
        put(A, Quality.MIN6, -1, 0, 2, 2, 1, 2)
        put(B, Quality.DOM7, -1, 2, 1, 2, 0, 2)
    }

    // Movable shapes, relative to the barre fret. E shape: root on the low E string. A shape: root on A.
    private val E_SHAPE = mapOf(
        Quality.MAJ to intArrayOf(0, 2, 2, 1, 0, 0),
        Quality.MIN to intArrayOf(0, 2, 2, 0, 0, 0),
        Quality.DOM7 to intArrayOf(0, 2, 0, 1, 0, 0),
        Quality.MIN7 to intArrayOf(0, 2, 0, 0, 0, 0),
        Quality.MAJ7 to intArrayOf(0, -1, 1, 1, 0, -1),
        Quality.SUS4 to intArrayOf(0, 2, 2, 2, 0, 0),
        Quality.MIN6 to intArrayOf(0, 2, 2, 0, 2, 0),
        Quality.MAJ6 to intArrayOf(0, 2, 2, 1, 2, 0),
        Quality.DIM7 to intArrayOf(0, -1, 2, 0, 2, 0),
    )
    private val A_SHAPE = mapOf(
        Quality.MAJ to intArrayOf(-1, 0, 2, 2, 2, 0),
        Quality.MIN to intArrayOf(-1, 0, 2, 2, 1, 0),
        Quality.DOM7 to intArrayOf(-1, 0, 2, 0, 2, 0),
        Quality.MIN7 to intArrayOf(-1, 0, 2, 0, 1, 0),
        Quality.MAJ7 to intArrayOf(-1, 0, 2, 1, 2, 0),
        Quality.SUS2 to intArrayOf(-1, 0, 2, 2, 0, 0),
        Quality.SUS4 to intArrayOf(-1, 0, 2, 2, 3, 0),
        Quality.DIM to intArrayOf(-1, 0, 1, 2, 1, -1),
        Quality.AUG to intArrayOf(-1, 0, 3, 2, 2, 1),
        Quality.MAJ6 to intArrayOf(-1, 0, 2, 2, 2, 2),
        Quality.MIN6 to intArrayOf(-1, 0, 2, 2, 1, 2),
        Quality.MINMAJ7 to intArrayOf(-1, 0, 2, 1, 1, 0),
        Quality.DIM7 to intArrayOf(-1, 0, 1, 2, 1, 2),
        Quality.HDIM7 to intArrayOf(-1, 0, 1, 0, 1, -1),
    )

    fun fingering(chord: Chord): Fingering? {
        val root = chord.root ?: return null
        OPEN[root to chord.quality]?.let { return Fingering(it, 1) }
        val candidates = buildList {
            E_SHAPE[chord.quality]?.let { add(movable(it, Math.floorMod(root - 4, 12))) }   // low E string = E (4)
            A_SHAPE[chord.quality]?.let { add(movable(it, Math.floorMod(root - 9, 12))) }   // A string = A (9)
        }
        if (candidates.isEmpty()) {
            // Rare quality without a shape: fall back to the plain triad.
            return if (chord.quality == chord.quality.simplified()) null else fingering(chord.simplified())
        }
        return candidates.minByOrNull { f -> f.frets.filter { it > 0 }.maxOrNull() ?: 0 }
    }

    private fun movable(shape: IntArray, fret: Int): Fingering {
        // fret 0 would be the open chord (handled above when it exists); otherwise play it at 12
        val at = if (fret == 0) 12 else fret
        val frets = IntArray(6) { if (shape[it] < 0) -1 else shape[it] + at }
        val base = at
        return Fingering(frets, base, barre = at)
    }
}
