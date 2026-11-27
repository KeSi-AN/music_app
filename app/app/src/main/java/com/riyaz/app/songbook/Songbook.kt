package com.riyaz.app.songbook

import com.riyaz.app.analysis.ChordSegment
import com.riyaz.app.analysis.SongAnalysis
import com.riyaz.app.lyrics.Lyrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * A chord sheet in your songbook. [text] is kept exactly as you pasted or wrote it (chords over lyrics,
 * ChordPro, or a mix); how you view it (key, capo, simple chords, scroll speed, text size) is stored beside it.
 */
data class Sheet(
    val id: String,
    val title: String,
    val artist: String = "",
    val text: String,
    val transpose: Int = 0,
    val capo: Int = 0,
    val simplify: Boolean = false,
    val speed: Int = 3,              // autoscroll speed 1..10
    val textScale: Float = 1f,
    val songId: String? = null,      // made from a song in the library: can play along with it
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("artist", artist).put("text", text)
        .put("transpose", transpose).put("capo", capo).put("simplify", simplify).put("speed", speed)
        .put("textScale", textScale.toDouble()).put("songId", songId ?: JSONObject.NULL)
        .put("created", created).put("updated", updated)

    companion object {
        fun newId() = "s" + System.currentTimeMillis().toString(36) + Random.nextInt(1296).toString(36)

        fun fromJson(j: JSONObject) = Sheet(
            j.getString("id"), j.optString("title"), j.optString("artist"), j.optString("text"),
            j.optInt("transpose"), j.optInt("capo"), j.optBoolean("simplify"), j.optInt("speed", 3).coerceIn(1, 10),
            j.optDouble("textScale", 1.0).toFloat(), if (j.isNull("songId")) null else j.optString("songId").ifBlank { null },
            j.optLong("created"), j.optLong("updated"),
        )
    }
}

/** An ordered list of sheets for a gig or a practice session. */
data class Setlist(val id: String, val name: String, val sheetIds: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("sheets", JSONArray(sheetIds))

    companion object {
        fun fromJson(j: JSONObject) = Setlist(j.getString("id"), j.optString("name"),
            j.optJSONArray("sheets")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList())
    }
}

/** files/songbook/<id>.json for each sheet, plus setlists.json. */
class SongbookStore(filesDir: File) {
    private val dir = File(filesDir, "songbook").apply { mkdirs() }
    private val setlistFile = File(dir, "setlists.json")

    fun sheets(): List<Sheet> = (dir.listFiles { f -> f.name.endsWith(".json") && f.name != "setlists.json" } ?: emptyArray())
        .mapNotNull { f -> runCatching { Sheet.fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.updated }

    fun save(s: Sheet) = File(dir, "${s.id}.json").writeText(s.toJson().toString(2))

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        saveSetlists(setlists().map { it.copy(sheetIds = it.sheetIds - id) })
    }

    fun setlists(): List<Setlist> = runCatching {
        val a = JSONArray(setlistFile.readText()); List(a.length()) { Setlist.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveSetlists(l: List<Setlist>) = setlistFile.writeText(JSONArray(l.map { it.toJson() }).toString(2))
}

/**
 * Turns a song Riyaz analysed into a chord sheet: chords placed over the (romanised) lyrics when the
 * lyrics are synced, otherwise a chord chart. Written as ChordPro, with a hidden {x_time} before each line
 * so the sheet can follow the song while it plays (other ChordPro apps ignore x_ directives).
 */
object SongSheet {
    fun build(title: String, artist: String?, analysis: SongAnalysis, lyrics: Lyrics?, durationSec: Double,
              romanize: (String) -> String): String {
        val flats = analysis.key.usesFlats
        val chords = cleanChords(analysis.segments)
        fun name(c: ChordSegment) = c.chord.name(flats)
        val sb = StringBuilder()
        sb.append("{title: ").append(title).append("}\n")
        if (!artist.isNullOrBlank()) sb.append("{artist: ").append(artist).append("}\n")
        sb.append("{key: ").append(analysis.key.shortName()).append("}\n")
        sb.append("{comment: Chords worked out by Riyaz from the recording}\n\n")

        fun chordRow(list: List<ChordSegment>) {
            if (list.isEmpty()) return
            barRows(chords, analysis.beats, analysis.barPhase, list.first().start, list.last().end) { it.chord.name(flats) }
                .forEach { sb.append(it).append("\n") }
        }

        val lines = lyrics?.lines.orEmpty()
        if (lines.isEmpty() || lyrics?.synced != true) {
            sb.append("{comment: Chords}\n")
            chordRow(chords)
            if (lines.isNotEmpty()) {
                sb.append("\n{comment: Lyrics (not synced, so the chords aren't placed over them)}\n")
                lines.forEach { sb.append(romanize(it.text)).append("\n") }
            }
            return sb.toString()
        }

        val first = lines.first().time!! - PICKUP
        val intro = chords.filter { it.start < first - 0.5 }
        if (intro.isNotEmpty()) { sb.append("{comment: Intro}\n"); chordRow(intro); sb.append("\n") }
        for ((i, line) in lines.withIndex()) {
            val t0 = line.time!! - PICKUP
            val next = lines.getOrNull(i + 1)?.time?.minus(PICKUP) ?: durationSec
            val end = minOf(next, t0 + MAX_LINE_SEC)
            val text = romanize(line.text).trim()
            val inLine = chords.filter { it.start >= t0 && it.start < end }
            val active = chords.lastOrNull { it.start < t0 && it.end > t0 }
            val placed = ArrayList<Pair<Int, String>>()
            if (active != null && (inLine.isEmpty() || inLine.first().start > t0 + 0.15 * (end - t0))) placed += 0 to name(active)
            var body = text
            for (c in inLine) {
                val want = ((c.start - t0) / (end - t0) * body.length).toInt()
                val (pos, b) = snap(body, want, placed.lastOrNull()?.first ?: -1)
                body = b
                placed += pos to name(c)
            }
            sb.append("{x_time: ").append(String.format(Locale.ROOT, "%.2f", line.time)).append("}\n")
            if (body.isBlank()) {
                sb.append(placed.joinToString(" ") { "[${it.second}]" }).append("\n")
            } else {
                val out = StringBuilder(body)
                for ((pos, n) in placed.asReversed()) out.insert(pos, "[$n]")
                sb.append(out).append("\n")
            }
            // an instrumental gap after the line
            val gap = chords.filter { it.start >= end && it.start < next }
            if (gap.isNotEmpty()) { sb.append("\n"); chordRow(gap); sb.append("\n") }
        }
        return sb.toString()
    }

    /**
     * A chord chart in bars ("| Gm | F Eb | Gm | F |", four bars a line) from the beat grid; a line that
     * repeats the one before becomes "(x2)", "(x3)"… Without beats, plain rows of eight chords.
     */
    fun barRows(chords: List<ChordSegment>, beats: List<Double>, barPhase: Int, from: Double, to: Double,
                name: (ChordSegment) -> String): List<String> {
        val starts = beats.indices.filter { Math.floorMod(it - barPhase, 4) == 0 }.map { beats[it] }
            .filter { it >= from - 0.3 && it < to - 0.3 }
        if (starts.size < 2) return chords.filter { it.start >= from - 0.01 && it.start < to }.chunked(8)
            .map { row -> row.joinToString(" ") { name(it) } }
        val barLen = starts[1] - starts[0]
        val bars = starts.mapIndexed { i, b ->
            val e = starts.getOrNull(i + 1) ?: (b + barLen)
            val inBar = chords.filter { it.end > b + 0.15 && it.start < e - 0.15 }.map(name)
            inBar.filterIndexed { k, n -> k == 0 || n != inBar[k - 1] }.joinToString(" ").ifEmpty { "-" }
        }
        val rows = bars.chunked(4).map { "| " + it.joinToString(" | ") + " |" }
        val out = ArrayList<String>()
        var repeat = 1
        for ((i, r) in rows.withIndex()) {
            if (i > 0 && r == rows[i - 1]) { repeat++; continue }
            if (repeat > 1) { out[out.size - 1] = out.last() + " (x$repeat)"; repeat = 1 }
            out += r
        }
        if (repeat > 1) out[out.size - 1] = out.last() + " (x$repeat)"
        return out
    }

    private const val PICKUP = 0.4         // chords often change just before the line's first word
    private const val MAX_LINE_SEC = 8.0   // a line rarely lasts longer; chords after that are an interlude

    /** Drops chord blips shorter than 0.4 s and joins repeats. */
    fun cleanChords(segments: List<ChordSegment>): List<ChordSegment> {
        val out = ArrayList<ChordSegment>()
        for (s in segments) {
            if (s.chord.isNone || s.end - s.start < 0.4) continue
            val last = out.lastOrNull()
            if (last != null && last.chord == s.chord) out[out.size - 1] = last.copy(end = s.end) else out += s
        }
        return out
    }

    /** The word start nearest [want] that comes after [after]; past the end, a space is added. */
    fun snap(text: String, want: Int, after: Int): Pair<Int, String> {
        val starts = text.indices.filter { (it == 0 || text[it - 1] == ' ') && text[it] != ' ' }.filter { it > after }
        if (starts.isEmpty()) {
            val t = if (text.isEmpty() || text.endsWith(" ")) text else "$text "
            return t.length to t
        }
        return starts.minByOrNull { kotlin.math.abs(it - want) }!! to text
    }
}
