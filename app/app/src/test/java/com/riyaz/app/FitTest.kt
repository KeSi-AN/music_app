package com.riyaz.app

import com.riyaz.app.analysis.Chord
import com.riyaz.app.analysis.ChordSegment
import com.riyaz.app.analysis.Quality
import com.riyaz.app.fit.KeyFit
import com.riyaz.app.fit.KeyHabits
import com.riyaz.app.fit.Understanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitTest {
    // a baritone-ish singer: comfortable A2 (45) .. E4 (64), happiest around B3 (59)
    private val low = 45f
    private val high = 64f
    private val center = 57f

    private fun seg(root: Int, q: Quality = Quality.MAJ, len: Double = 4.0, at: Double = 0.0) = ChordSegment(at, at + len, Chord(root, q))

    @Test fun songAlreadyInRangeStaysInItsKey() {
        val s = KeyFit.suggest(50f, 62f, 56f, low, high, center, emptyList())
        assertEquals(0, s.shift)
        assertEquals(0, s.octave)
        assertTrue(s.reason, s.reason.startsWith("The original key suits you"))
    }

    @Test fun songTooHighComesDown() {
        // tune goes up to F#4 (66), two above the comfortable top
        val s = KeyFit.suggest(54f, 66f, 60f, low, high, center, emptyList())
        assertTrue("shift ${s.shift}", s.shift in -4..-2)
        assertTrue(s.sungHigh <= high)
        assertTrue(s.reason, s.reason.contains("within your comfortable top"))
    }

    @Test fun femaleSongIsSungAnOctaveDown() {
        // a female lead around G4-A4
        val s = KeyFit.suggest(62f, 74f, 68f, low, high, center, emptyList())
        assertEquals(-1, s.octave)
        assertTrue(s.sungHigh <= high + 0.5f)
        assertTrue(s.reason, s.reason.contains("octave below"))
        assertEquals("female voice", Understanding.singer(68f))
        assertEquals("male voice", Understanding.singer(56f))
    }

    @Test fun capoGivesOpenShapes() {
        // Bb Eb F Gm: all barre chords; capo 3 makes them G C D Em
        val chords = listOf(seg(10), seg(3, at = 4.0), seg(5, at = 8.0), seg(7, Quality.MIN, at = 12.0))
        assertEquals(3, KeyFit.bestCapo(chords, 0))
        // already open (G C D Em): no capo
        val open = listOf(seg(7), seg(0, at = 4.0), seg(2, at = 8.0), seg(4, Quality.MIN, at = 12.0))
        assertEquals(0, KeyFit.bestCapo(open, 0))
    }

    @Test fun habitIsLearnedFromOverrides() {
        var h = KeyHabits()
        h = h.record("a", 0, -1)
        h = h.record("b", 2, 1)
        assertEquals(0, h.bias())           // not enough yet
        h = h.record("c", -1, -2)
        assertEquals(-1, h.bias())
        h = h.record("c", -1, -1)           // changing your mind on a song replaces its entry
        assertEquals(3, h.choices.size)
        assertEquals(KeyHabits.fromJson(h.toJson()), h)

        val plain = KeyFit.suggest(50f, 62f, 56f, low, high, center, emptyList())
        val withHabit = KeyFit.suggest(50f, 62f, 56f, low, high, center, emptyList(), learnedBias = -1)
        assertEquals(plain.shift - 1, withHabit.shift)
        assertEquals(plain.shift, withHabit.base)
        assertTrue(withHabit.reason, withHabit.reason.contains("habit of going 1 lower"))
    }

    @Test fun habitDoesNotPushTheSongOutOfRange() {
        // already right at the top: a "go higher" habit would put it 3 above
        val s = KeyFit.suggest(54f, 66f, 60f, low, high, center, emptyList(), learnedBias = 3)
        assertEquals(s.base, s.shift)
    }

    @Test fun languageFromScriptWordsOrTitle() {
        assertEquals("Hindi", Understanding.language(listOf("तुझे देखा तो ये जाना सनम", "प्यार होता है दीवाना सनम"), "Tujhe Dekha"))
        assertEquals("Telugu", Understanding.language(listOf("నువ్వే నా ప్రాణం", "నిన్నే ప్రేమిస్తా"), "x"))
        assertEquals("Hindi", Understanding.language(listOf("tujhe dekha to ye jaana sanam", "pyaar hota hai deewana sanam",
            "ab yahan se kahan jaayen hum", "teri baahon mein mar jaayen hum"), "Tujhe Dekha"))
        assertEquals("English", Understanding.language(listOf("I just want to feel your love", "baby you know that I'm never alone",
            "when the night is on my mind"), "Song"))
        assertEquals("Telugu", Understanding.language(emptyList(), "Vellake | Full Song | Telugu Indie"))
        assertNull(Understanding.language(emptyList(), "Uska Hi Bana"))
    }

    @Test fun pace() {
        assertEquals("slow", Understanding.pace(72.0))
        assertEquals("mid-tempo", Understanding.pace(96.0))
        assertEquals("upbeat", Understanding.pace(128.0))
    }
}
