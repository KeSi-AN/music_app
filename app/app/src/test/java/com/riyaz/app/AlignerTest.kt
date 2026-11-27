package com.riyaz.app

import com.riyaz.app.lyrics.LyricsAligner
import com.riyaz.app.lyrics.TimedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignerTest {
    private val lyrics = listOf(
        "Mere hisse ki khushi ko, hansi ko tu chahe aadha kar",
        "Chahe le-le tu meri zindagi, par ye mujh se vada kar",
        "Ae khuda, ae khuda, jab bana uska hi bana",
        "Ae khuda, ae khuda, jab bana uska hi bana",
        "Uska hoon, usmen hoon, usse hoon, usi ka rahne de",
    )

    @Test fun soundAlikeSpellings() {
        assertEquals(LyricsAligner.key("pyaar"), LyricsAligner.key("pyar"))
        assertEquals(LyricsAligner.key("khuda"), LyricsAligner.key("kuda"))
        assertEquals(LyricsAligner.key("zindagi"), LyricsAligner.key("jindagi"))
        assertEquals(LyricsAligner.key("wo"), LyricsAligner.key("vo"))
        assertEquals(LyricsAligner.key("mujhe"), LyricsAligner.key("muje"))
    }

    @Test fun linesGetTheirTimesDespiteMishearing() {
        // Whisper's version: misspelt, a word missing, an intro hum, and the chorus twice
        val heard = listOf(
            TimedText(2.0, 6.0, "hmm hmm"),
            TimedText(10.0, 16.0, "mere hise ki kushi ko hasi ko tu chahe adha kar"),
            TimedText(16.5, 23.0, "chahe le le tu meri jindagi par ye muj se wada kar"),
            TimedText(30.0, 35.0, "ai kuda ai kuda jab bana uska hi bana"),
            TimedText(36.0, 41.0, "ai khuda ai khuda jab banaa uska hi banaa"),
            TimedText(45.0, 52.0, "uska hun usme hun use hun usi ka rehne de"),
        )
        val r = LyricsAligner.align(lyrics, heard)!!
        val expected = listOf(10.0, 16.5, 30.0, 36.0, 45.0)
        r.times.zip(expected).forEachIndexed { i, (got, want) -> assertEquals("line $i", want, got, 0.8) }
        assertTrue(r.matched > 0.7f)
    }

    @Test fun unheardLinesAreFilledInBetween() {
        val heard = listOf(
            TimedText(10.0, 16.0, "mere hisse ki khushi ko hansi ko tu chahe aadha kar"),
            // line 2 not heard at all
            TimedText(30.0, 35.0, "ae khuda ae khuda jab bana uska hi bana"),
            TimedText(36.0, 41.0, "ae khuda ae khuda jab bana uska hi bana"),
            TimedText(45.0, 52.0, "uska hoon usmen hoon usse hoon usi ka rahne de"),
        )
        val r = LyricsAligner.align(lyrics, heard)!!
        assertTrue("between its neighbours: ${r.times[1]}", r.times[1] > 12.0 && r.times[1] < 30.0)
        assertTrue(r.times.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun refusesWhenTheWordsDontMatch() {
        val heard = listOf(TimedText(0.0, 60.0, "a completely different english song about the weather and trains"))
        assertNull(LyricsAligner.align(lyrics, heard))
    }
}
