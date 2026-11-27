package com.riyaz.app.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** One lyric line; [time] (seconds) is null until the line is synced. */
data class LyricLine(val text: String, val time: Double?)

data class Lyrics(
    val lines: List<LyricLine>,
    val source: String,          // "lrclib" or "lrclib+tap" (timings tapped by the user)
    val track: String,
    val artist: String,
) {
    val synced get() = lines.isNotEmpty() && lines.all { it.time != null }

    /** Index of the line being sung at [sec], or -1 before the first line. */
    fun lineAt(sec: Double): Int {
        var idx = -1
        for (i in lines.indices) {
            val t = lines[i].time ?: break
            if (t <= sec) idx = i else break
        }
        return idx
    }

    fun toJson(): JSONObject = JSONObject()
        .put("source", source).put("track", track).put("artist", artist)
        .put("lines", JSONArray(lines.map { JSONArray(listOf(it.text, it.time ?: JSONObject.NULL)) }))

    companion object {
        fun fromJson(j: JSONObject): Lyrics {
            val a = j.getJSONArray("lines")
            return Lyrics(
                lines = List(a.length()) { i ->
                    val l = a.getJSONArray(i)
                    LyricLine(l.getString(0), if (l.isNull(1)) null else l.getDouble(1))
                },
                source = j.optString("source"), track = j.optString("track"), artist = j.optString("artist"),
            )
        }
    }
}

/**
 * Lyrics from LRCLIB (lrclib.net): free, open, no account. Synced (LRC) lyrics are used only when the
 * recording is the same length; a cover or live version gets the plain text, to be synced by tapping.
 */
object LyricsService {
    private const val UA = "Riyaz/0.1 (personal music app)"
    private const val MAX_LENGTH_DIFF = 3.0   // seconds

    data class Query(val title: String, val artist: String?)

    /** Strips YouTube clutter: "(Official Video)", "| Lyrical", "ft. X", "cover", "HD"... */
    fun queryFor(title: String, channel: String?): Query {
        var t = title
        t = t.replace(Regex("""[\(\[\{【].*?[\)\]\}】]"""), " ")
        t = t.split('|', '｜', '•').first()
        t = t.replace(Regex("""(?i)\b(official|music|lyric(al)?s?|video|audio|full\s+song|song|cover|unplugged|version|hd|4k|remastered|live)\b"""), " ")
        t = t.replace(Regex("""(?i)\b(ft|feat|featuring)\.?\s.*$"""), " ")
        // "Song - Telugu", "Song - Hindi": a language label, not an artist
        t = t.replace(Regex("""(?i)\s+[-–—]\s+(hindi|telugu|tamil|bengali|bangla|marathi|punjabi|malayalam|kannada|gujarati|urdu|english)\s*$"""), " ")
        var artist = channel?.replace(Regex("""(?i)\s*-\s*topic$"""), "")?.replace(Regex("""(?i)vevo$"""), "")?.trim()
        // "Artist - Title" titles
        val dash = t.split(Regex("""\s+[-–—]\s+"""))
        if (dash.size >= 2) {
            val (a, b) = dash[0].trim() to dash.drop(1).joinToString(" ").trim()
            // the part matching the channel name is the artist; otherwise assume "Title - Artist(cover)"
            if (artist != null && a.contains(artist, ignoreCase = true)) { t = b; artist = a } else { t = a; if (b.isNotBlank()) artist = b }
        }
        t = t.replace(Regex("""\s+"""), " ").trim()
        return Query(t, artist?.replace(Regex("""\s+"""), " ")?.trim()?.ifBlank { null })
    }

    fun fetch(query: Query, durationSec: Double): Lyrics? {
        val hits = search(mapOf("track_name" to query.title) + (query.artist?.let { mapOf("artist_name" to it) } ?: emptyMap()))
            .ifEmpty { search(mapOf("q" to listOfNotNull(query.title, query.artist).joinToString(" "))) }
            .ifEmpty { search(mapOf("q" to query.title)) }
        if (hits.isEmpty()) return null
        val synced = hits.filter { it.optString("syncedLyrics").isNotBlank() }
        val exact = synced.minByOrNull { abs(it.optDouble("duration", 0.0) - durationSec) }
            ?.takeIf { abs(it.optDouble("duration", 0.0) - durationSec) <= MAX_LENGTH_DIFF }
        if (exact != null) {
            return Lyrics(parseLrc(exact.getString("syncedLyrics")), "lrclib", exact.optString("trackName"), exact.optString("artistName"))
        }
        // different recording (cover, live, edit): keep the words, drop the timings
        val any = hits.firstOrNull { it.optString("plainLyrics").isNotBlank() } ?: synced.firstOrNull() ?: return null
        val text = any.optString("plainLyrics").ifBlank { parseLrc(any.optString("syncedLyrics")).joinToString("\n") { it.text } }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LyricLine(it, null) }
        if (lines.isEmpty()) return null
        return Lyrics(lines, "lrclib", any.optString("trackName"), any.optString("artistName"))
    }

    fun parseLrc(lrc: String): List<LyricLine> {
        val re = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        val out = ArrayList<LyricLine>()
        for (raw in lrc.lines()) {
            val stamps = re.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            if (text.isEmpty()) continue           // instrumental gaps
            for (m in stamps) out += LyricLine(text, m.groupValues[1].toInt() * 60 + m.groupValues[2].toDouble())
        }
        return out.sortedBy { it.time }
    }

    private fun search(params: Map<String, String>): List<JSONObject> {
        val q = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = URL("https://lrclib.net/api/search?$q").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return try {
            if (conn.responseCode != 200) return emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            List(arr.length()) { arr.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }
}

object LyricsStore {
    fun file(songDir: File) = File(songDir, "lyrics.json")
    fun load(songDir: File): Lyrics? = runCatching { Lyrics.fromJson(JSONObject(file(songDir).readText())) }.getOrNull()
    fun save(songDir: File, l: Lyrics) = file(songDir).writeText(l.toJson().toString())
}
