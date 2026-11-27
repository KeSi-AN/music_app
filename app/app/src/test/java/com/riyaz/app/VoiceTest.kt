package com.riyaz.app

import com.riyaz.app.voice.Notes
import com.riyaz.app.voice.RangeAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class VoiceTest {
    private val rnd = Random(7)
    /** frames at 62.5/s: slide linearly from a to b over slideS, then hold b for holdS, with vibrato and jitter */
    private fun sing(a: Float, b: Float, slideS: Float, holdS: Float): List<Float> {
        val out = ArrayList<Float>()
        val nSlide = (slideS * 62.5f).toInt(); val nHold = (holdS * 62.5f).toInt()
        for (i in 0 until nSlide) out += a + (b - a) * i / nSlide + rnd.nextFloat() * 0.2f - 0.1f
        for (i in 0 until nHold) out += b + 0.25f * kotlin.math.sin(i * 0.35f) + rnd.nextFloat() * 0.1f - 0.05f
        return out
    }

    @Test fun notesAndCents() {
        assertEquals("A4", Notes.name(69)); assertEquals("C4", Notes.name(60)); assertEquals("E2", Notes.name(40))
        assertEquals(69f, Notes.midi(440f), 1e-4f)
        assertEquals(57f, Notes.midi(220f), 1e-4f)
        assertEquals(12f, Notes.cents(Notes.midi(440f * 1.00695f)), 0.2f)
    }

    @Test fun lowMaleRange() {
        val hold = sing(52f, 52f, 0f, 4f)                        // E3 held
        val down = sing(52f, 43f, 5f, 1.2f)                      // slide to G2 and hold
        val up = sing(52f, 64f, 6f, 1.2f).toMutableList()        // slide to E4 and hold
        // a few octave errors in the middle of the slide, as real trackers produce
        for (i in 100 until 104) up[i] = up[i] + 12f
        val (p, why) = RangeAnalysis.analyse(hold, down, up, 0L)
        assertNull(why); assertNotNull(p)
        assertEquals(43f, p!!.comfortableLow, 0.5f)
        assertEquals(64f, p.comfortableHigh, 0.5f)
        assertEquals(52f, p.center, 0.5f)
        assertEquals("bass", p.voiceType())   // G2-E4 sits closest to the textbook bass range E2-E4
    }

    @Test fun rejectsSilenceAndNoSlide() {
        assertNotNull(RangeAnalysis.analyse(emptyList(), emptyList(), emptyList(), 0L).second)
        val flat = sing(55f, 55f, 0f, 4f)
        assertNotNull(RangeAnalysis.analyse(flat, flat, flat, 0L).second)
    }
}
