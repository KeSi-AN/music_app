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
    /** Where it came from: a YouTube watch URL, or null for a file on the phone. */
    val sourceUrl: String? = null,
    val thumbnailUrl: String? = null,
    /** Capo fret for the chord shapes shown (the audio is unaffected). */
    val capo: Int = 0,
    /** Show plain major/minor chords instead of 7ths, sus etc. */
    val simplifyChords: Boolean = false,
    /** The beat tracker sometimes counts double or half time; the user can correct the BPM shown. */
    val bpmFactor: Float = 1f,
    /** Karaoke vocal level when the instrumental exists: 0 = no vocals, 1 = the original recording. */
    val vocalLevel: Float = 1f,
    /** The key Riyaz fitted to your voice (before your habit), once it has; overrides are learned against it. */
    val fitBase: Int? = null,
) {
    val durationMs: Long get() = frames * 1000L / sampleRate
}

/**
 * Keeps decoded songs in app storage: songs/<id>/{audio.pcm, meta.json, peaks.bin}.
 * Decoded audio is large (~10 MB per minute), so only the most recent [MAX_SONGS] are kept
 * (YouTube songs can be fetched again).
 */
class SongLibrary(private val context: Context) {
    private val root = File(context.filesDir, "songs").apply { mkdirs() }

    fun pcmFile(song: Song) = File(File(root, song.id), "audio.pcm")
    fun dir(song: Song) = File(root, song.id)

    fun recent(): List<Song> =
        root.listFiles().orEmpty()
            .mapNotNull { dir -> runCatching { readMeta(dir) }.getOrNull() }
            .sortedByDescending { it.lastOpened }

    /**
     * Loudness overview for the waveform: RMS per bin, contrast-stretched to 0..1.
     * Peaks are useless here because loud modern masters hit full scale almost everywhere.
     * Built from the PCM once and cached.
     */
    fun overview(song: Song): FloatArray? = runCatching {
        val f = File(File(root, song.id), "overview_v2.bin")
        if (f.exists()) {
            DataInputStream(f.inputStream().buffered()).use { s -> FloatArray(s.readInt()) { s.readFloat() } }
        } else {
            buildOverview(pcmFile(song), song.frames).also { o ->
                DataOutputStream(f.outputStream().buffered()).use { s ->
                    s.writeInt(o.size)
                    o.forEach { s.writeFloat(it) }
                }
            }
        }
    }.getOrNull()

    private fun buildOverview(pcm: File, frames: Long): FloatArray {
        val bins = OVERVIEW_BINS
        val perBin = maxOf(1L, frames / bins)
        val sums = DoubleArray(bins)
        val counts = LongArray(bins)
        val buf = ByteArray(1 shl 16)
        var frame = 0L
        pcm.inputStream().use { input ->
            while (true) {
                // Fill the whole buffer so every chunk stays a multiple of 32 bytes (except the last).
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                if (n <= 0) break
                var i = 0
                // Every 8th frame is plenty for a 600-bar overview and 8x cheaper.
                // (Reads are 64 KiB, a multiple of 32 bytes, so the stride stays frame-aligned.)
                while (i + 3 < n) {
                    val l = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort() / 32768.0
                    val r = ((buf[i + 3].toInt() shl 8) or (buf[i + 2].toInt() and 0xff)).toShort() / 32768.0
                    val b = minOf(bins - 1, (frame / perBin).toInt())
                    sums[b] += (l * l + r * r) / 2
                    counts[b]++
                    frame += 8
                    i += 32
                }
            }
        }
        val rms = FloatArray(bins) { if (counts[it] > 0) kotlin.math.sqrt(sums[it] / counts[it]).toFloat() else 0f }
        // Contrast: map the quiet floor (5th percentile of non-silent bins) to ~0.08 and the max to 1.
        val sorted = rms.filter { it > 1e-4f }.sorted()
        if (sorted.isEmpty()) return rms
        val lo = sorted[(sorted.size * 0.05).toInt()]
        val hi = sorted.last()
        return FloatArray(bins) { i -> (0.08f + 0.92f * ((rms[i] - lo) / (hi - lo + 1e-6f))).coerceIn(0.03f, 1f) }
    }

    /** Decodes [uri] (or reuses an earlier decode of the same file). */
    fun import(uri: Uri, onProgress: (Float) -> Unit): Song {
        val (name, size) = queryNameAndSize(uri)
        val id = sha1("$name|$size").take(16)
        val dir = File(root, id)
        runCatching { readMeta(dir) }.getOrNull()?.let { existing ->
            if (pcmFile(existing).length() == existing.frames * 4) return touch(existing)
        }

        dir.mkdirs()
        val result = decodeInto(dir, uri, onProgress)
        val (title, artist) = readTags(uri, name)
        return finish(Song(id, title, artist, result.sampleRate, result.frames))
    }

    /** Library id for a YouTube video, so the same video is only downloaded and decoded once. */
    fun youtubeId(videoId: String) = "yt-" + videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Returns the cached song for this id if its audio is complete. */
    fun cached(id: String): Song? =
        runCatching { readMeta(File(root, id)) }.getOrNull()
            ?.takeIf { pcmFile(it).length() == it.frames * 4 }
            ?.let { touch(it) }

    /** Work folder for a download; it becomes the song's folder. */
    fun folderFor(id: String): File = File(root, id).apply { mkdirs() }

    /**
     * Decodes an already-downloaded file in [id]'s folder, then deletes it. Only the decoded audio is
     * kept; the song can be fetched again from [sourceUrl].
     */
    fun importDownloaded(
        id: String, file: File, title: String, artist: String?,
        sourceUrl: String, thumbnailUrl: String?, onProgress: (Float) -> Unit,
    ): Song {
        val dir = File(root, id)
        val result = try {
            decodeInto(dir, android.net.Uri.fromFile(file), onProgress)
        } finally {
            file.delete()
        }
        return finish(Song(id, title, artist, result.sampleRate, result.frames, sourceUrl = sourceUrl, thumbnailUrl = thumbnailUrl))
    }

    /** Throws away a half-finished download or decode. */
    fun discard(id: String) {
        File(root, id).deleteRecursively()
    }

    private fun decodeInto(dir: File, uri: Uri, onProgress: (Float) -> Unit): AudioDecoder.Result {
        val tmp = File(dir, "audio.pcm.part")
        val result = try {
            AudioDecoder(context).decode(uri, tmp, onProgress)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
        tmp.renameTo(File(dir, "audio.pcm"))
        return result
    }

    private fun finish(song: Song): Song {
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
            .put("sourceUrl", song.sourceUrl ?: "").put("thumbnailUrl", song.thumbnailUrl ?: "")
            .put("capo", song.capo).put("simplifyChords", song.simplifyChords).put("bpmFactor", song.bpmFactor.toDouble())
            .put("vocalLevel", song.vocalLevel.toDouble())
        song.fitBase?.let { j.put("fitBase", it) }
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
            artist = j.optString("artist").replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "").trim().ifBlank { null },
            sampleRate = j.getInt("sampleRate"),
            frames = j.getLong("frames"),
            semitones = j.optInt("semitones"),
            cents = j.optInt("cents"),
            tempo = j.optDouble("tempo", 1.0).toFloat(),
            naturalVoice = j.optBoolean("naturalVoice"),
            lastOpened = j.optLong("lastOpened"),
            sourceUrl = j.optString("sourceUrl").ifBlank { null },
            thumbnailUrl = j.optString("thumbnailUrl").ifBlank { null },
            capo = j.optInt("capo"),
            simplifyChords = j.optBoolean("simplifyChords"),
            bpmFactor = j.optDouble("bpmFactor", 1.0).toFloat(),
            vocalLevel = j.optDouble("vocalLevel", 1.0).toFloat(),
            fitBase = if (j.has("fitBase")) j.getInt("fitBase") else null,
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
        const val MAX_SONGS = 12
        const val OVERVIEW_BINS = 600
    }
}
