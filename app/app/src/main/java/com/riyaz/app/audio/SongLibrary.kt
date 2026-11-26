package com.riyaz.app.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** A decoded song on the phone, plus the key/speed you last used for it. */
data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val sampleRate: Int,
    val frames: Long,
    val semitones: Int = 0,
    val cents: Int = 0,
    val tempo: Float = 1f,
    val naturalVoice: Boolean = false,
    val lastOpened: Long = System.currentTimeMillis(),
) {
    val durationMs: Long get() = frames * 1000L / sampleRate
}

/**
 * Keeps decoded songs in app storage: songs/<id>/{audio.pcm, meta.json, peaks.bin}.
 * Decoded audio is large (~10 MB per minute), so only the most recent [MAX_SONGS] are kept.
 */
class SongLibrary(private val context: Context) {
    private val root = File(context.filesDir, "songs").apply { mkdirs() }

    fun pcmFile(song: Song) = File(File(root, song.id), "audio.pcm")

    fun recent(): List<Song> =
        root.listFiles().orEmpty()
            .mapNotNull { dir -> runCatching { readMeta(dir) }.getOrNull() }
            .sortedByDescending { it.lastOpened }

    fun peaks(song: Song): FloatArray? = runCatching {
        DataInputStream(File(File(root, song.id), "peaks.bin").inputStream().buffered()).use { s ->
            FloatArray(s.readInt()) { s.readFloat() }
        }
    }.getOrNull()

    /** Decodes [uri] (or reuses an earlier decode of the same file). */
    fun import(uri: Uri, onProgress: (Float) -> Unit): Song {
        val (name, size) = queryNameAndSize(uri)
        val id = sha1("$name|$size").take(16)
        val dir = File(root, id)
        runCatching { readMeta(dir) }.getOrNull()?.let { existing ->
            if (pcmFile(existing).length() == existing.frames * 4) return touch(existing)
        }

        dir.mkdirs()
        val tmp = File(dir, "audio.pcm.part")
        val result = try {
            AudioDecoder(context).decode(uri, tmp, onProgress)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
        tmp.renameTo(File(dir, "audio.pcm"))
        DataOutputStream(File(dir, "peaks.bin").outputStream().buffered()).use { s ->
            s.writeInt(result.peaks.size)
            result.peaks.forEach { s.writeFloat(it) }
        }

        val (title, artist) = readTags(uri, name)
        val song = Song(id, title, artist, result.sampleRate, result.frames)
        save(song)
        evictOld()
        return song
    }

    fun save(song: Song) {
        val dir = File(root, song.id).apply { mkdirs() }
        val j = JSONObject()
            .put("id", song.id).put("title", song.title).put("artist", song.artist ?: "")
            .put("sampleRate", song.sampleRate).put("frames", song.frames)
            .put("semitones", song.semitones).put("cents", song.cents)
            .put("tempo", song.tempo.toDouble()).put("naturalVoice", song.naturalVoice)
            .put("lastOpened", song.lastOpened)
        File(dir, "meta.json").writeText(j.toString(2))
    }

    fun touch(song: Song): Song = song.copy(lastOpened = System.currentTimeMillis()).also { save(it) }

    fun delete(song: Song) {
        File(root, song.id).deleteRecursively()
    }

    private fun readMeta(dir: File): Song {
        val j = JSONObject(File(dir, "meta.json").readText())
        return Song(
            id = j.getString("id"),
            title = j.getString("title"),
            artist = j.optString("artist").ifBlank { null },
            sampleRate = j.getInt("sampleRate"),
            frames = j.getLong("frames"),
            semitones = j.optInt("semitones"),
            cents = j.optInt("cents"),
            tempo = j.optDouble("tempo", 1.0).toFloat(),
            naturalVoice = j.optBoolean("naturalVoice"),
            lastOpened = j.optLong("lastOpened"),
        )
    }

    private fun evictOld() {
        recent().drop(MAX_SONGS).forEach { delete(it) }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "Song"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        return name to size
    }

    private fun readTags(uri: Uri, fileName: String): Pair<String, String?> {
        val fallback = fileName.substringBeforeLast('.')
        return runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
                val a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
                (t?.takeIf { it.isNotEmpty() } ?: fallback) to a?.takeIf { it.isNotEmpty() }
            }
        }.getOrDefault(fallback to null)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_SONGS = 8
    }
}
