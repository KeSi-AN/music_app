package com.riyaz.app.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The small files that hold your work on a song: your settings (key, capo, speed, fit…), its lyrics (with
 * any timings you tapped), its chords and its sung melody. Audio is never kept here: it can be fetched again.
 */
object SongFiles {
    val NAMES = listOf("meta.json", "lyrics.json", "analysis.json", "melody.bin", "heard.json")

    /** Settings you chose for a song, taken from a saved meta.json (everything but what describes the audio). */
    val SETTINGS = listOf("semitones", "cents", "tempo", "naturalVoice", "capo", "simplifyChords", "bpmFactor", "vocalLevel", "fitBase")

    /** Copies [from]'s settings into [into] (a song's own meta.json). */
    fun mergeSettings(into: JSONObject, from: JSONObject): JSONObject {
        for (k in SETTINGS) if (from.has(k)) into.put(k, from.get(k))
        return into
    }
}

/**
 * Remembers songs that dropped out of the library (it keeps only the most recent ones), so opening the
 * same song again — the same YouTube video or the same file — brings back your settings and lyrics.
 * files/remembered/<songId>/{meta.json, lyrics.json, analysis.json, melody.bin}
 */
class SongMemory(filesDir: File) {
    val root = File(filesDir, "remembered")

    /** Called before a song's folder is deleted. */
    fun remember(songDir: File) {
        val dest = File(root, songDir.name)
        for (n in SongFiles.NAMES) {
            val f = File(songDir, n)
            if (f.exists()) { dest.mkdirs(); f.copyTo(File(dest, n), overwrite = true) }
        }
    }

    /**
     * Called when a song arrives in the library: puts back what was remembered for it. The new meta.json
     * keeps its own audio details and takes the remembered settings. Returns true if anything was restored.
     */
    fun restoreInto(songDir: File): Boolean {
        val src = File(root, songDir.name)
        if (!src.isDirectory) return false
        val meta = File(songDir, "meta.json")
        val old = runCatching { JSONObject(File(src, "meta.json").readText()) }.getOrNull()
        if (old != null && meta.exists()) {
            meta.writeText(SongFiles.mergeSettings(JSONObject(meta.readText()), old).toString(2))
        }
        for (n in SongFiles.NAMES - "meta.json") {
            val f = File(src, n)
            if (f.exists() && !File(songDir, n).exists()) f.copyTo(File(songDir, n))
        }
        src.deleteRecursively()
        return true
    }

    fun ids(): List<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
}

data class BackupSummary(val songs: Int, val sheets: Int, val setlists: Int, val voice: Boolean, val skipped: Int = 0) {
    fun describe(verb: String) = buildString {
        append(verb).append(" ")
        val parts = listOfNotNull(
            if (voice) "your voice profile" else null,
            "$songs song${if (songs == 1) "" else "s"}",
            "$sheets chord sheet${if (sheets == 1) "" else "s"}",
            if (setlists > 0) "$setlists setlist${if (setlists == 1) "" else "s"}" else null,
        )
        append(parts.dropLast(1).joinToString(", "))
        if (parts.size > 1) append(" and ")
        append(parts.last())
        append(".")
    }
}

/**
 * Backup as one small zip: your voice profile (range tests, what singing taught it, key habits), your
 * songbook, and each song's settings, lyrics, chords and melody (library and remembered songs). Restoring
 * replaces the voice profile, adds sheets and setlists (keeping whichever copy of a sheet is newer), and
 * puts song settings back — straight into songs in the library, or remembered for when you open them.
 */
object Backup {
    const val VERSION = 1
    private const val MAX_ENTRY = 8L * 1024 * 1024

    fun write(filesDir: File, out: OutputStream, now: Long = System.currentTimeMillis()): BackupSummary {
        var songs = 0
        var sheets = 0
        var setlists = 0
        var voice = false
        ZipOutputStream(out).use { zip ->
            fun put(name: String, f: File) {
                zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            fun putText(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            putText("riyaz-backup.json", JSONObject().put("version", VERSION).put("created", now).toString(2))
            File(filesDir, "voice_profile.json").takeIf { it.exists() }?.let { put("voice_profile.json", it); voice = true }
            File(filesDir, "songbook").listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".json")) return@forEach
                put("songbook/${f.name}", f)
                if (f.name == "setlists.json") setlists = runCatching { JSONArray(f.readText()).length() }.getOrDefault(0) else sheets++
            }
            // songs in the library win over remembered copies of the same song
            val dirs = LinkedHashMap<String, File>()
            File(filesDir, "remembered").listFiles()?.filter { it.isDirectory }?.forEach { dirs[it.name] = it }
            File(filesDir, "songs").listFiles()?.filter { it.isDirectory && File(it, "meta.json").exists() }?.forEach { dirs[it.name] = it }
            for ((id, dir) in dirs) {
                if (!File(dir, "meta.json").exists()) continue
                for (n in SongFiles.NAMES) File(dir, n).takeIf { it.exists() }?.let { put("songs/$id/$n", it) }
                songs++
            }
        }
        return BackupSummary(songs, sheets, setlists, voice)
    }

    /** Reads a backup made by [write]. Throws IllegalArgumentException if it isn't one. */
    fun restore(filesDir: File, input: InputStream): BackupSummary {
        // read everything first, so a broken file changes nothing
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                require(!name.startsWith("/") && ".." !in name.split('/')) { "Unexpected file in the backup: $name" }
                val bytes = zip.readNBytesCompat(MAX_ENTRY + 1)
                require(bytes.size <= MAX_ENTRY) { "A file in the backup is too large: $name" }
                entries[name] = bytes
            }
        }
        val header = entries["riyaz-backup.json"]?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
        require(header != null) { "This isn't a Riyaz backup." }
        require(header.optInt("version") <= VERSION) { "This backup is from a newer version of Riyaz." }

        var voice = false
        entries["voice_profile.json"]?.let { bytes ->
            JSONObject(String(bytes))   // must parse
            File(filesDir, "voice_profile.json").writeBytes(bytes); voice = true
        }

        // songbook: newer copy of each sheet wins; setlists merged by id
        val book = File(filesDir, "songbook").apply { mkdirs() }
        var sheets = 0
        for ((name, bytes) in entries) {
            if (!name.startsWith("songbook/") || name.endsWith("/setlists.json")) continue
            val fileName = name.removePrefix("songbook/")
            if ('/' in fileName) continue
            val incoming = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: continue
            val local = File(book, fileName)
            val localUpdated = runCatching { JSONObject(local.readText()).optLong("updated") }.getOrNull()
            if (localUpdated == null || incoming.optLong("updated") > localUpdated) { local.writeBytes(bytes); sheets++ }
        }
        var setlists = 0
        entries["songbook/setlists.json"]?.let { bytes ->
            val incoming = runCatching { JSONArray(String(bytes)) }.getOrNull() ?: return@let
            val file = File(book, "setlists.json")
            val local = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
            val byId = LinkedHashMap<String, JSONObject>()
            for (i in 0 until local.length()) local.getJSONObject(i).let { byId[it.getString("id")] = it }
            for (i in 0 until incoming.length()) incoming.getJSONObject(i).let { byId[it.getString("id")] = it; setlists++ }
            file.writeText(JSONArray(byId.values.toList()).toString(2))
        }

        // songs: into the library if the song is there, otherwise remembered for when it comes back
        val songIds = entries.keys.filter { it.startsWith("songs/") }.mapNotNull { it.split('/').getOrNull(1) }.toSet()
        val songsDir = File(filesDir, "songs")
        val memory = SongMemory(filesDir)
        var skipped = 0
        for (id in songIds) {
            if (!Regex("[A-Za-z0-9_-]{1,64}").matches(id)) { skipped++; continue }
            val files = SongFiles.NAMES.associateWith { entries["songs/$id/$it"] }.filterValues { it != null }
            val backupMeta = files["meta.json"]?.let { runCatching { JSONObject(String(it!!)) }.getOrNull() }
            if (backupMeta == null) { skipped++; continue }
            val inLibrary = File(songsDir, id)
            if (File(inLibrary, "meta.json").exists()) {
                val meta = File(inLibrary, "meta.json")
                meta.writeText(SongFiles.mergeSettings(JSONObject(meta.readText()), backupMeta).toString(2))
                for ((n, b) in files) if (n != "meta.json") File(inLibrary, n).writeBytes(b!!)
            } else {
                val dest = File(memory.root, id).apply { mkdirs() }
                for ((n, b) in files) File(dest, n).writeBytes(b!!)
            }
        }
        return BackupSummary(songIds.size - skipped, sheets, setlists, voice, skipped)
    }

    private fun InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n); total += n
        }
        return out.toByteArray()
    }
}
