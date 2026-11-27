package com.riyaz.app

import com.riyaz.app.analysis.Chord
import com.riyaz.app.analysis.ChordSegment
import com.riyaz.app.analysis.Key
import com.riyaz.app.analysis.Quality
import com.riyaz.app.analysis.SongAnalysis
import com.riyaz.app.lyrics.LyricLine
import com.riyaz.app.lyrics.Lyrics
import com.riyaz.app.songbook.ChordSheetParser
import com.riyaz.app.songbook.SheetLine
import com.riyaz.app.songbook.SongSheet
import com.riyaz.app.songbook.TextChord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongbookTest {

    @Test fun chordNames() {
        assertEquals(TextChord(9, "m7"), TextChord.parse("Am7"))
        assertEquals(TextChord(10, "", 2), TextChord.parse("Bb/D"))
        assertEquals(TextChord(6, "m"), TextChord.parse("F#m"))
        assertEquals(TextChord(7, "(add9)"), TextChord.parse("G(add9)"))
        assertEquals(TextChord(0, "sus4"), TextChord.parse("Csus4"))
        for (word in listOf("Dad", "Be", "Go", "Main", "Dil", "Every", "Cause", "Am)", "Cm(")) assertNull(word, TextChord.parse(word))
        assertEquals("Bm7", TextChord.parse("Am7")!!.transposed(2).name(false))
        assertEquals("Eb/G", TextChord.parse("D/F#")!!.transposed(1).name(true))
        assertEquals("Am", TextChord.parse("Am7")!!.name(false, simplify = true))
        assertEquals(Quality.DOM7, TextChord.parse("G9")!!.quality())
        assertEquals(Quality.MIN7, TextChord.parse("Em9")!!.quality())
        assertEquals(Quality.MAJ, TextChord.parse("Cadd9")!!.quality())
    }

    @Test fun chordsOverLyrics() {
        val text = """
            [Verse 1]
            Am           F
            Tujhe dekha to ye jaana sanam
            C              G
            Pyaar hota hai deewana sanam

            Chorus:
            | Am | F | C | G |
            e|-----0---0-----|
        """.trimIndent()
        val s = ChordSheetParser.parse(text)
        assertEquals(SheetLine.Section("Verse 1"), s.lines[0])
        val l1 = s.lines[1] as SheetLine.Lyrics
        assertEquals(listOf("Am", "F"), l1.parts.mapNotNull { it.chord?.name(false) })
        assertEquals("Tujhe dekha t", l1.parts[0].text)
        assertEquals("o ye jaana sanam", l1.parts[1].text)
        val l2 = s.lines[2] as SheetLine.Lyrics
        assertEquals("Pyaar hota hai ", l2.parts[0].text)
        assertEquals(SheetLine.Blank, s.lines[3])
        assertEquals(SheetLine.Section("Chorus"), s.lines[4])
        val bars = s.lines[5] as SheetLine.Lyrics
        assertTrue(bars.chordOnly)
        assertEquals(listOf("Am", "F", "C", "G"), bars.parts.mapNotNull { it.chord?.name(false) })
        assertTrue(s.lines[6] is SheetLine.Tab)
        assertEquals(9, s.guessKey()!!.tonic)    // A minor
        assertTrue(s.guessKey()!!.minor)
    }

    @Test fun lyricLinesAreNotMistakenForChords() {
        assertNull(ChordSheetParser.chordLine("A love that never ends"))
        assertNull(ChordSheetParser.chordLine("Am I wrong"))
        assertTrue(ChordSheetParser.chordLine("Am  G  (x2)") != null)
        assertTrue(ChordSheetParser.chordLine("C  G/B  Am7  Fmaj7") != null)
    }

    @Test fun chordPro() {
        val text = """
            {title: Uska Hi Bana}
            {artist: Arijit Singh}
            {key: Am}
            {capo: 2}
            {start_of_chorus}
            {x_time: 12.5}
            Us[Am]ka hi ba[F]na
            {end_of_chorus}
            {c: Repeat twice}
            {start_of_tab}
            e|--3--|
            {end_of_tab}
        """.trimIndent()
        val s = ChordSheetParser.parse(text)
        assertEquals("Uska Hi Bana", s.title)
        assertEquals("Arijit Singh", s.artist)
        assertEquals(2, s.capo)
        assertEquals(SheetLine.Section("Chorus"), s.lines[0])
        val l = s.lines[1] as SheetLine.Lyrics
        assertEquals(12.5, l.time!!, 1e-9)
        assertEquals(listOf("Us", "ka hi ba", "na"), l.parts.map { it.text })
        assertEquals(listOf(null, "Am", "F"), l.parts.map { it.chord?.name(false) })
        assertEquals(SheetLine.Comment("Repeat twice"), s.lines[2])
        assertEquals(SheetLine.Tab("e|--3--|"), s.lines[3])
        assertTrue(s.timed)
    }

    @Test fun ultimateGuitarPaste() {
        val text = "[Intro]\n[ch]Em[/ch] [ch]C[/ch] [ch]G[/ch] [ch]D[/ch]\n\n[Verse]\n[tab][ch]Em[/ch]          [ch]C[/ch]\nI found a love for me[/tab]"
        val s = ChordSheetParser.parse(text)
        assertEquals(SheetLine.Section("Intro"), s.lines[0])
        assertEquals(4, (s.lines[1] as SheetLine.Lyrics).parts.count { it.chord != null })
        val v = s.lines[4] as SheetLine.Lyrics
        assertEquals(listOf("Em", "C"), v.parts.mapNotNull { it.chord?.name(false) })
        assertEquals("I found a lo", v.parts[0].text)
        assertTrue(ChordSheetParser.looksLikeSheet(text))
        assertFalse(ChordSheetParser.looksLikeSheet("https://youtu.be/abc"))
        assertFalse(ChordSheetParser.looksLikeSheet("uska hi bana mack vocals"))
        assertEquals(null, ChordSheetParser.guessTitle(text))
        assertEquals("Perfect - Ed Sheeran", ChordSheetParser.guessTitle("Perfect - Ed Sheeran\n\n$text"))
    }

    @Test fun chordChartInBars() {
        fun seg(a: Double, b: Double, root: Int, q: Quality = Quality.MAJ) = ChordSegment(a, b, Chord(root, q))
        // Gm F | Eb Eb repeated: bars are 2 s (4 beats of 0.5 s)
        val chords = listOf(seg(0.0, 2.0, 7, Quality.MIN), seg(2.0, 3.0, 5), seg(3.0, 8.0, 3),
            seg(8.0, 10.0, 7, Quality.MIN), seg(10.0, 11.0, 5), seg(11.0, 16.0, 3))
        val beats = List(32) { it * 0.5 }
        val rows = SongSheet.barRows(chords, beats, 0, 0.0, 16.0) { it.chord.name(true) }
        assertEquals(listOf("| Gm | F Eb | Eb | Eb | (x2)"), rows)
        val parsed = ChordSheetParser.parse(rows.joinToString("\n"))
        val line = parsed.lines.single() as SheetLine.Lyrics
        assertTrue(line.chordOnly)
        assertEquals(listOf("Gm", "F", "Eb", "Eb", "Eb"), line.parts.mapNotNull { it.chord?.name(true) })
    }

    @Test fun sheetFromAnalysedSong() {
        fun seg(a: Double, b: Double, root: Int, q: Quality = Quality.MAJ) = ChordSegment(a, b, Chord(root, q))
        val analysis = SongAnalysis(90.0, emptyList(), 0, Key(9, true), listOf(
            seg(0.0, 4.0, 9, Quality.MIN), seg(4.0, 4.2, 2), // a blip, dropped
            seg(4.2, 8.0, 5), seg(8.0, 10.0, 0), seg(10.0, 12.0, 7), seg(12.0, 30.0, 9, Quality.MIN),
        ))
        val lyrics = Lyrics(listOf(LyricLine("tujhe dekha to ye jaana", 8.0), LyricLine("pyaar hota hai", 12.0)), "lrclib", "t", "a")
        val text = SongSheet.build("Test", "Someone", analysis, lyrics, 30.0) { it }
        val s = ChordSheetParser.parse(text)
        assertEquals("Test", s.title)
        assertEquals("Am", s.key)
        val lyricLines = s.lines.filterIsInstance<SheetLine.Lyrics>().filter { !it.chordOnly }
        assertEquals(2, lyricLines.size)
        assertEquals(8.0, lyricLines[0].time!!, 1e-9)
        // C at the start of line 1, G about halfway through it
        assertEquals(listOf("C", "G"), lyricLines[0].parts.mapNotNull { it.chord?.name(false) })
        assertEquals("tujhe dekha to ye jaana", lyricLines[0].parts.joinToString("") { it.text })
        assertEquals(listOf("Am"), lyricLines[1].parts.mapNotNull { it.chord?.name(false) })
        // the intro chords come first, without the 0.2 s blip
        val intro = s.lines.filterIsInstance<SheetLine.Lyrics>().first()
        assertEquals(listOf("Am", "F"), intro.parts.mapNotNull { it.chord?.name(false) })
    }
}
