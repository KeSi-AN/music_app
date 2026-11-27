package com.riyaz.app

import com.riyaz.app.voice.LearnedVoice
import com.riyaz.app.voice.Melody
import com.riyaz.app.voice.SessionAnalysis
import com.riyaz.app.voice.SongPreference
import com.riyaz.app.voice.SungFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LearningTest {
    private val rnd = Random(3)

    /** A melody of [notes] (MIDI) each [noteSec] long with a short gap, at 20 ms frames. */
    private fun melody(notes: List<Float>, noteSec: Double = 0.6): Melody {
        val frames = ArrayList<Float>()
        for (n in notes) {
            repeat((noteSec / 0.02).toInt()) { frames += n + (rnd.nextFloat() - 0.5f) * 0.1f }
            repeat(5) { frames += Float.NaN }
        }
        return Melody(frames.toFloatArray())
    }

    /** Mic frames every 16 ms singing [melody] shifted by [shift] semitones, heard [delay] s late. */
    private fun sing(m: Melody, shift: Float, delay: Double, err: (Float) -> Float = { 0f }): List<SungFrame> {
        val out = ArrayList<SungFrame>()
        var t = 0.0
        while (t < m.midi.size * 0.02) {
            val target = m.at(t)
            if (!target.isNaN()) out += SungFrame(t + delay, target + shift + err(target) + (rnd.nextFloat() - 0.5f) * 0.15f)
            t += 0.016
        }
        return out
    }

    private val tune = listOf(57f, 59f, 60f, 62f, 64f, 62f, 60f, 59f, 57f, 64f, 65f, 67f)   // A3 .. G4

    @Test fun perfectSingingAnOctaveDownWithDelay() {
        val m = melody(tune)
        assertEquals(tune.size, m.notes().size)
        val r = SessionAnalysis.analyse(m, sing(m, -12f - 2f, 0.25), keyShift = -2f)   // key -2, octave down
        assertEquals(-1, r.octave)
        assertEquals(0.25, r.delaySec, 0.05)
        assertEquals(1f, r.accuracy, 0.01f)
        assertEquals(tune.size, r.sungNotes.size)
        assertTrue(r.strainedNotes.isEmpty())
        assertEquals(67f - 2f - 12f, r.highestComfortable!!, 0.3f)
    }

    @Test fun flatOnHighNotesIsStrain() {
        val m = melody(tune)
        val r = SessionAnalysis.analyse(m, sing(m, 0f, 0.2) { target -> if (target >= 64.5f) -0.9f else 0f }, 0f)
        assertEquals(listOf(65, 67), r.strainedNotes)
        assertEquals(64f, r.highestComfortable!!, 0.3f)
    }

    @Test fun skippedNotesAreNotSung() {
        val m = melody(tune)
        val frames = sing(m, 0f, 0.2).filter { it.songSec < 4.0 }   // stopped singing after 4 s
        val r = SessionAnalysis.analyse(m, frames, 0f)
        // only the notes in the stretch you sang count (plus a second either side)
        assertTrue(r.notes.size < tune.size)
        assertTrue(r.sungNotes.size >= r.notes.size - 2)
    }

    @Test fun learningAccumulatesAndFades() {
        val m = melody(tune)
        val good = SessionAnalysis.analyse(m, sing(m, 0f, 0.2) { t -> if (t >= 66.5f) -0.9f else 0f }, 0f)
        val pref = SongPreference("s1", "Test", 0f, 0, 57f, 67f, good.accuracy, 0L)
        var lv = LearnedVoice()
        lv = lv.plus(good, pref)
        assertEquals(1, lv.sessions)
        assertEquals(64, lv.learnedHigh())          // E4 was sung well twice; F4 only once so far; G4 strained
        lv = lv.plus(good, pref)
        assertEquals(65, lv.learnedHigh())           // G4 (67) was strained both times
        assertEquals(57, lv.learnedLow())
        assertEquals(1, lv.songs.size)               // same song replaces its entry
        // after many sessions without singing high notes, old evidence fades away
        val low = melody(listOf(55f, 57f, 59f, 60f, 62f, 60f, 59f, 57f, 55f))
        val lowR = SessionAnalysis.analyse(low, sing(low, 0f, 0.2), 0f)
        repeat(12) { lv = lv.plus(lowR, pref.copy(songId = "s2")) }
        assertEquals(62, lv.learnedHigh())
        assertEquals(55, lv.learnedLow())
        // JSON round trip
        val back = LearnedVoice.fromJson(lv.toJson())
        assertEquals(lv.learnedHigh(), back.learnedHigh())
        assertEquals(lv.sessions, back.sessions)
        assertEquals(lv.songs.size, back.songs.size)
    }
}
