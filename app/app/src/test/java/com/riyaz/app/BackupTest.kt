package com.riyaz.app

import com.riyaz.app.backup.Backup
import com.riyaz.app.backup.SongMemory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    private fun tmp(): File = Files.createTempDirectory("riyaz").toFile()

    private fun song(files: File, id: String, semitones: Int, lyrics: String? = null) {
        val d = File(files, "songs/$id").apply { mkdirs() }
        File(d, "meta.json").writeText(JSONObject().put("id", id).put("title", "Song $id").put("frames", 1000).put("sampleRate", 48000)
            .put("semitones", semitones).put("capo", 2).put("fitBase", -1).toString())
        File(d, "audio.pcm").writeBytes(ByteArray(4000))
        lyrics?.let { File(d, "lyrics.json").writeText(it) }
    }

    @Test fun roundTrip() {
        val a = tmp()
        File(a, "voice_profile.json").writeText("""{"current":{"comfortableLow":45}}""")
        File(a, "songbook").mkdirs()
        File(a, "songbook/s1.json").writeText(JSONObject().put("id", "s1").put("title", "Mine").put("updated", 100).toString())
        File(a, "songbook/setlists.json").writeText(JSONArray().put(JSONObject().put("id", "l1").put("name", "Gig").put("sheets", JSONArray())).toString())
        song(a, "yt-abc", -2, """{"lines":[["tapped",1.5]]}""")
        File(a, "remembered/yt-old").mkdirs()
        File(a, "remembered/yt-old/meta.json").writeText("""{"id":"yt-old","semitones":3}""")

        val zip = ByteArrayOutputStream()
        val w = Backup.write(a, zip)
        assertEquals(2, w.songs); assertEquals(1, w.sheets); assertEquals(1, w.setlists); assertTrue(w.voice)
        assertFalse("no audio in a backup", String(zip.toByteArray(), Charsets.ISO_8859_1).contains("audio.pcm"))

        // a fresh phone with one of the songs downloaded again (default settings, no lyrics)
        val b = tmp()
        song(b, "yt-abc", 0)
        File(b, "songbook").mkdirs()
        File(b, "songbook/s1.json").writeText(JSONObject().put("id", "s1").put("title", "Edited later").put("updated", 200).toString())
        val r = Backup.restore(b, ByteArrayInputStream(zip.toByteArray()))
        assertEquals(2, r.songs); assertTrue(r.voice)
        assertEquals(1, r.sheets)   // counted, but the phone's copy of s1 is newer, so it stays
        assertEquals("Edited later", JSONObject(File(b, "songbook/s1.json").readText()).getString("title"))
        val meta = JSONObject(File(b, "songs/yt-abc/meta.json").readText())
        assertEquals(-2, meta.getInt("semitones")); assertEquals(2, meta.getInt("capo")); assertEquals(-1, meta.getInt("fitBase"))
        assertEquals(1000, meta.getInt("frames"))   // the audio details stay the phone's own
        assertTrue(File(b, "songs/yt-abc/lyrics.json").readText().contains("tapped"))
        assertEquals(1, JSONArray(File(b, "songbook/setlists.json").readText()).length())
        // the song that isn't on the phone is remembered for when it's opened again
        assertEquals(listOf("yt-old"), SongMemory(b).ids())
    }

    @Test fun rememberedSongComesBack() {
        val f = tmp()
        song(f, "yt-x", -1, """{"lines":[]}""")
        val mem = SongMemory(f)
        mem.remember(File(f, "songs/yt-x"))
        File(f, "songs/yt-x").deleteRecursively()
        // downloaded again later: fresh meta with default settings
        song(f, "yt-x", 0)
        assertTrue(mem.restoreInto(File(f, "songs/yt-x")))
        assertEquals(-1, JSONObject(File(f, "songs/yt-x/meta.json").readText()).getInt("semitones"))
        assertTrue(File(f, "songs/yt-x/lyrics.json").exists())
        assertTrue(mem.ids().isEmpty())
        assertFalse(mem.restoreInto(File(f, "songs/yt-x")))
    }

    @Test fun rejectsOtherZips() {
        val f = tmp()
        fun zipOf(vararg entries: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
            return out.toByteArray()
        }
        try { Backup.restore(f, ByteArrayInputStream(zipOf("photo.jpg" to "x"))); fail() } catch (e: IllegalArgumentException) {}
        try {
            Backup.restore(f, ByteArrayInputStream(zipOf("riyaz-backup.json" to """{"version":1}""", "../evil.txt" to "x"))); fail()
        } catch (e: IllegalArgumentException) {}
        assertFalse(File(f.parentFile, "evil.txt").exists())
        assertFalse(File(f, "voice_profile.json").exists())
    }
}
