package com.riyaz.app

import com.riyaz.app.analysis.Chord
import com.riyaz.app.analysis.ChordSegment
import com.riyaz.app.analysis.Quality
import com.riyaz.app.play.ChordMatch
import com.riyaz.app.play.PlayAlongSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlayAlongTest {
    private val rnd = Random(5)

    /** What the mic would give for a strummed chord: its harmonic template, plus a little noise. */
    private fun heard(c: Chord, noise: Float = 0.15f): FloatArray {
        val t = ChordMatch.template(c)
        val mx = t.maxOrNull()!!
        return FloatArray(12) { (t[it] / mx + rnd.nextFloat() * noise).coerceAtMost(1f) }
    }

    @Test fun telling_chords_apart() {
        for (r in 0 until 12) for (q in listOf(Quality.MAJ, Quality.MIN)) {
            val c = Chord(r, q)
            assertEquals(c, ChordMatch.best(heard(c)))
            assertTrue(ChordMatch.matches(heard(c), c))
            assertFalse("$c vs a fourth up", ChordMatch.matches(heard(c), c.transposed(5)))
            assertFalse("$c vs a tone up", ChordMatch.matches(heard(c), c.transposed(2)))
        }
        // playing the plain chord for a 7th is fine; C (A C E G minus A) counts for Am7
        assertTrue(ChordMatch.matches(heard(Chord(7, Quality.MAJ)), Chord(7, Quality.DOM7)))
        assertTrue(ChordMatch.matches(heard(Chord(0, Quality.MAJ)), Chord(9, Quality.MIN7)))
        assertFalse(ChordMatch.matches(heard(Chord(2, Quality.MAJ)), Chord(9, Quality.MIN7)))
    }

    @Test fun session_scores_each_chord() {
        // G (0-4 s), C (4-8 s), D (8-12 s); the song is played 2 semitones up, so you hear A, D, E
        val segs = listOf(ChordSegment(0.0, 4.0, Chord(7)), ChordSegment(4.0, 8.0, Chord(0)), ChordSegment(8.0, 12.0, Chord(2)))
        val s = PlayAlongSession(segs, keyShift = 2)
        assertEquals(Chord(9), s.expectedAt(1.0))
        var t = 0.3
        while (t < 12.0) {
            val played = when {
                t < 4.0 -> Chord(9)            // A: right
                t < 8.0 -> Chord(2)            // D: right
                else -> Chord(9)               // A where E is expected: wrong
            }
            s.add(t, heard(played), 0.05f)
            t += 0.1
        }
        // quiet readings don't count
        assertNull(s.add(2.0, heard(Chord(0)), 0.0005f))
        val r = s.result()
        assertEquals(listOf(true, true, false), r.chords.map { it.ok })
        assertEquals(66, r.percent)
        assertEquals(listOf(Chord(4) to Chord(9)), r.trouble())   // E, which sounded like A
    }
}
