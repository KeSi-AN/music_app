package com.riyaz.app

import com.riyaz.app.analysis.Chord
import com.riyaz.app.analysis.GuitarChords
import com.riyaz.app.analysis.Key
import com.riyaz.app.analysis.MusicTheory
import com.riyaz.app.analysis.Quality
import com.riyaz.app.lyrics.LyricsService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsQueryTest {
    @Test fun cleansYouTubeTitles() {
        assertEquals(LyricsService.Query("Uska Hi Bana", "Mack Vocals"), LyricsService.queryFor("Uska Hi Bana - Mack Vocals cover", "Mack Vocals"))
        assertEquals("Kaa Karoon Sajni", LyricsService.queryFor("Kaa Karoon Sajni ｜ Somlata And The Aces Feat Sudipto Banerjee ｜ Somlata Acharyya Chowdhury", "Somlata Acharyya Chowdhury").title)
        assertEquals(LyricsService.Query("Tum Hi Ho", "Arijit Singh"), LyricsService.queryFor("Arijit Singh - Tum Hi Ho (Official Video) | Aashiqui 2", "Arijit Singh"))
        assertEquals(LyricsService.Query("Buttabomma", "Armaan Malik, Thaman S"), LyricsService.queryFor("Buttabomma - Telugu", "Armaan Malik, Thaman S"))
        assertEquals("Kesariya", LyricsService.queryFor("Kesariya [Full Song HD] ft. Arijit Singh", "Sony Music India").title)
        assertEquals("Armaan Malik", LyricsService.queryFor("Song", "Armaan Malik - Topic").artist)
    }

    @Test fun titleSimilarityRejectsOtherSongs() {
        // real case: LRCLIB's loose search returned "Hasi" for "Uska Hi Bana", with the same length
        assert(LyricsService.titleSimilarity("Hasi", "Uska Hi Bana") < 0.6)
        assert(LyricsService.titleSimilarity("Uska Hi Banana", "Uska Hi Bana") >= 0.6)
        assert(LyricsService.titleSimilarity("Kaa Karoon Sajni", "Kaa Karoon Sajni") >= 0.99)
        assert(LyricsService.titleSimilarity("Tum Hi Ho (From \"Aashiqui 2\")", "Tum Hi Ho") >= 0.6)
        assert(LyricsService.titleSimilarity("Shaayar", "shaayar") >= 0.99)
    }

    @Test fun choosesSyncedOnlyForSameLength() {
        fun hit(title: String, dur: Double, synced: String?, plain: String?) = org.json.JSONObject()
            .put("trackName", title).put("artistName", "x").put("duration", dur)
            .put("syncedLyrics", synced ?: org.json.JSONObject.NULL).put("plainLyrics", plain ?: org.json.JSONObject.NULL)
        val original = hit("Uska Hi Banana", 328.0, "[00:10.00] uska hi bana\n[00:14.00] line two", "uska hi bana\nline two")
        // the cover is 274 s: words but no timings
        val forCover = LyricsService.choose(listOf(original), 274.0)!!
        assertEquals(false, forCover.synced)
        assertEquals(listOf("uska hi bana", "line two"), forCover.lines.map { it.text })
        // the original recording: synced
        val forOriginal = LyricsService.choose(listOf(original), 327.0)!!
        assertEquals(true, forOriginal.synced)
        assertEquals(10.0, forOriginal.lines[0].time!!, 1e-6)
    }

    @Test fun parsesLrc() {
        val l = LyricsService.parseLrc("[ar:x]\n[00:07.10] first\n[00:10.94]second\n[00:12.00]\n[01:02.5][01:30.25] chorus")
        assertEquals(listOf("first", "second", "chorus", "chorus"), l.map { it.text })
        assertEquals(7.1, l[0].time!!, 1e-6)
        assertEquals(62.5, l[2].time!!, 1e-6)
        assertEquals(90.25, l[3].time!!, 1e-6)
    }
}

class MusicTheoryTest {
    @Test fun namesAndTransposition() {
        val am7 = Chord.parse("A:min7")
        assertEquals("Am7", am7.name(flats = false))
        assertEquals("Gm7", am7.transposed(-2).name(false))
        assertEquals("Bbm7", am7.transposed(1).name(flats = true))
        assertEquals("A:min7", am7.code())
        assertEquals("Am", am7.simplified().name(false))
        assertEquals(Chord.NONE, Chord.fromBtc(169))
        assertEquals(Chord(9, Quality.MAJ), Chord.fromBtc(9 * 14 + 1))
    }

    @Test fun flatKeys() {
        assertEquals(true, Key(5, false).usesFlats)     // F major
        assertEquals(true, Key(2, true).usesFlats)      // D minor
        assertEquals(false, Key(9, true).usesFlats)     // A minor
        assertEquals("F# minor", Key(6, true).name())
    }

    @Test fun keyFromChords() {
        // i - VII - VI - V in A minor (Uska Hi Bana's progression)
        val segs = listOf(Chord(9, Quality.MIN) to 8.0, Chord(7, Quality.MAJ) to 4.0, Chord(5, Quality.MAJ) to 4.0, Chord(4, Quality.MAJ) to 4.0)
        assertEquals(Key(9, true), MusicTheory.keyFromChords(segs))
        // I - V - vi - IV in G major
        val pop = listOf(Chord(7, Quality.MAJ) to 8.0, Chord(2, Quality.MAJ) to 4.0, Chord(4, Quality.MIN) to 4.0, Chord(0, Quality.MAJ) to 4.0)
        assertEquals(Key(7, false), MusicTheory.keyFromChords(pop))
    }
}

class GuitarChordsTest {
    @Test fun openAndBarreShapes() {
        assertArrayEquals(intArrayOf(-1, 0, 2, 2, 1, 0), GuitarChords.fingering(Chord(9, Quality.MIN))!!.frets)   // Am open
        val f = GuitarChords.fingering(Chord(5, Quality.MAJ))!!                                              // F barre, fret 1
        assertArrayEquals(intArrayOf(1, 3, 3, 2, 1, 1), f.frets)
        val bm = GuitarChords.fingering(Chord(11, Quality.MIN))!!                                            // Bm, A shape fret 2
        assertArrayEquals(intArrayOf(-1, 2, 4, 4, 3, 2), bm.frets)
        val csm = GuitarChords.fingering(Chord(1, Quality.MIN))!!                                            // C#m at fret 4
        assertEquals(4, csm.baseFret)
        assertNull(GuitarChords.fingering(Chord.NONE))
        for (r in 0 until 12) for (q in Quality.entries) assertNotNull("$r $q", GuitarChords.fingering(Chord(r, q)))
    }
}
