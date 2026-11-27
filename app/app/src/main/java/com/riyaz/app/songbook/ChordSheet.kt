package com.riyaz.app.songbook

import com.riyaz.app.analysis.Chord
import com.riyaz.app.analysis.Key
import com.riyaz.app.analysis.MusicTheory
import com.riyaz.app.analysis.Quality

/**
 * A chord as written in a sheet: root, the suffix exactly as written ("m7", "sus4", "add9"…) and an
 * optional bass note ("/F#"). Transposing moves root and bass; the suffix stays as the writer wrote it.
 */
data class TextChord(val root: Int, val suffix: String, val bass: Int? = null) {

    fun transposed(n: Int) = TextChord(Math.floorMod(root + n, 12), suffix, bass?.let { Math.floorMod(it + n, 12) })

    fun name(flats: Boolean, simplify: Boolean = false): String {
        if (simplify) return MusicTheory.noteName(root, flats) + if (quality().minor) "m" else ""
        return MusicTheory.noteName(root, flats) + suffix + (bass?.let { "/" + MusicTheory.noteName(it, flats) } ?: "")
    }

    /** The nearest chord the diagrams know (a 9th shows as a 7th, add9 as the plain triad). */
    fun quality(): Quality {
        val s = suffix.replace("(", "").replace(")", "")
        EXACT[s]?.let { return it }
        val minor = (s.startsWith("m") && !s.startsWith("maj")) || s.startsWith("min") || s.startsWith("-")
        return when {
            s.startsWith("sus2") -> Quality.SUS2
            s.contains("sus") -> Quality.SUS4
            minor && Regex("7|9|11|13").containsMatchIn(s) -> Quality.MIN7
            minor -> Quality.MIN
            s.startsWith("maj") || s.startsWith("M") || s.startsWith("Δ") -> Quality.MAJ7
            Regex("^(7|9|11|13)").containsMatchIn(s) -> Quality.DOM7
            else -> Quality.MAJ
        }
    }

    fun toChord(): Chord = Chord(root, quality())

    companion object {
        private val EXACT = mapOf(
            "" to Quality.MAJ, "M" to Quality.MAJ, "maj" to Quality.MAJ,
            "m" to Quality.MIN, "min" to Quality.MIN, "-" to Quality.MIN,
            "7" to Quality.DOM7, "maj7" to Quality.MAJ7, "M7" to Quality.MAJ7, "Δ" to Quality.MAJ7, "Δ7" to Quality.MAJ7,
            "m7" to Quality.MIN7, "min7" to Quality.MIN7, "-7" to Quality.MIN7,
            "dim" to Quality.DIM, "°" to Quality.DIM, "dim7" to Quality.DIM7, "°7" to Quality.DIM7,
            "m7b5" to Quality.HDIM7, "ø" to Quality.HDIM7, "ø7" to Quality.HDIM7, "min7b5" to Quality.HDIM7,
            "aug" to Quality.AUG, "+" to Quality.AUG, "6" to Quality.MAJ6, "m6" to Quality.MIN6,
            "sus2" to Quality.SUS2, "sus4" to Quality.SUS4, "sus" to Quality.SUS4,
            "mmaj7" to Quality.MINMAJ7, "mM7" to Quality.MINMAJ7, "minmaj7" to Quality.MINMAJ7,
        )
        private val NOTE = Regex("""([A-G])([#b♯♭]?)""")
        private val CHORD = Regex(
            """([A-G])([#b♯♭]?)((?:maj|min|dim|aug|sus|add|omit|no|alt|m|M|Δ|°|ø|\+|-|\d{1,2}|[#b♯♭](?=\d)|\(|\))*)(?:/([A-G][#b♯♭]?))?"""
        )

        fun note(s: String): Int? {
            val m = NOTE.matchEntire(s) ?: return null
            val base = MusicTheory.SHARP.indexOf(m.groupValues[1])
            return Math.floorMod(base + when (m.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }, 12)
        }

        /** "Am7", "F#m", "Bb/D", "Csus4", "G(add9)" → a chord; anything else → null. */
        fun parse(token: String): TextChord? {
            val m = CHORD.matchEntire(token.trim()) ?: return null
            val root = note(m.groupValues[1] + m.groupValues[2]) ?: return null
            val suffix = m.groupValues[3]
            if (suffix.count { it == '(' } != suffix.count { it == ')' }) return null
            return TextChord(root, suffix, m.groups[4]?.value?.let { note(it) })
        }
    }
}

/** One chord over a stretch of lyric. [chord] null = plain text; [raw] keeps non-chord marks like "|" or "x2". */
data class Part(val chord: TextChord?, val text: String, val raw: String? = null)

sealed interface SheetLine {
    /** A lyric line with chords (or a chord-only line when every part's text is empty). [time] = when it's sung, if known. */
    data class Lyrics(val parts: List<Part>, val time: Double? = null) : SheetLine {
        val chordOnly get() = parts.all { it.text.isBlank() }
        val hasChords get() = parts.any { it.chord != null }
    }
    data class Section(val name: String) : SheetLine
    data class Comment(val text: String) : SheetLine
    data class Tab(val text: String) : SheetLine
    data object Blank : SheetLine
}

data class ParsedSheet(
    val title: String?,
    val artist: String?,
    val key: String?,
    val capo: Int?,
    val lines: List<SheetLine>,
) {
    val chords get() = lines.filterIsInstance<SheetLine.Lyrics>().flatMap { l -> l.parts.mapNotNull { it.chord } }
    val timed get() = lines.any { it is SheetLine.Lyrics && it.time != null }

    /** The written key, or one guessed from the chords; used to spell transposed chords with sharps or flats. */
    fun guessKey(): Key? {
        key?.let { k -> TextChord.parse(k.trim())?.let { return Key(it.root, it.quality().minor) } }
        val c = chords
        if (c.isEmpty()) return null
        return MusicTheory.keyFromChords(c.map { it.toChord() to 1.0 } + (c.first().toChord() to 1.0))
    }
}

/**
 * Reads the chord sheets people actually paste: chords written above the lyrics (Ultimate Guitar style,
 * including its [ch]/[tab] tags), ChordPro ([Am] inside the lyrics, {title: …} directives), section
 * headings like [Chorus] or "Verse 2:", and guitar tab.
 */
object ChordSheetParser {
    private val IGNORABLE = Regex("""^(\|+|:?\|\|?:?|-+|/+|%|\*+|x\d+|\(x\d+\)|\d+x|N\.?C\.?|\(|\)|\.+|~)$""", RegexOption.IGNORE_CASE)
    private val SECTION_WORDS = Regex(
        """^(intro|verse|pre-?chorus|chorus|bridge|outro|interlude|solo|instrumental|refrain|hook|coda|ending|break|antara|mukhda|sthayi|tag|riff)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TAB_LINE = Regex("""^\s*[eBGDAEbgda]\s*[|:][-\d|hpbrsx/\\~^()=.\s]{4,}$""")
    private val DIRECTIVE = Regex("""^\{\s*([a-zA-Z_]+)\s*(?::\s*(.*?))?\s*\}$""")

    /** Ultimate Guitar copy-paste tags. */
    private fun clean(text: String) = text
        .replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').replace(' ', ' ')
        .replace("[ch]", "").replace("[/ch]", "").replace("[tab]", "").replace("[/tab]", "")

    /** Columns and tokens of a line that has only chords (and bar marks), or null if it's a lyric line. */
    fun chordLine(line: String): List<Pair<Int, String>>? {
        val tokens = Regex("""\S+""").findAll(line).map { it.range.first to it.value }.toList()
        if (tokens.isEmpty()) return null
        var chords = 0
        for ((_, t) in tokens) {
            val bare = t.trim('(', ')', '|')
            when {
                bare.isNotEmpty() && TextChord.parse(bare) != null -> chords++
                IGNORABLE.matches(t) -> {}
                else -> return null
            }
        }
        return if (chords > 0) tokens else null
    }

    private fun sectionName(line: String): String? {
        val t = line.trim()
        // [Verse 1], [Chorus] — but not [Am]
        Regex("""^\[([^\[\]]+)\]$""").matchEntire(t)?.let { m ->
            val inner = m.groupValues[1].trim()
            if (TextChord.parse(inner) == null) return inner
        }
        // Verse 1:  /  CHORUS
        Regex("""^([A-Za-z][A-Za-z\- ]{1,20}?\s*\d*)\s*:?$""").matchEntire(t)?.let { m ->
            if (SECTION_WORDS.containsMatchIn(m.groupValues[1])) return m.groupValues[1].trim()
        }
        return null
    }

    private fun chordPartsFromTokens(tokens: List<Pair<Int, String>>): List<Part> = tokens.map { (_, t) ->
        val c = TextChord.parse(t.trim('(', ')', '|'))
        if (c != null) Part(c, "") else Part(null, "", raw = t)
    }

    /** Chords at columns over a lyric line → parts (lyric split where each chord sits). */
    fun merge(tokens: List<Pair<Int, String>>, lyric: String): List<Part> {
        val out = ArrayList<Part>()
        val first = tokens.first().first
        if (first > 0 && lyric.isNotEmpty()) out += Part(null, lyric.substring(0, minOf(first, lyric.length)))
        for ((i, tok) in tokens.withIndex()) {
            val from = tok.first
            val to = tokens.getOrNull(i + 1)?.first ?: Int.MAX_VALUE
            val text = if (from >= lyric.length) "" else lyric.substring(from, minOf(to, lyric.length))
            val c = TextChord.parse(tok.second.trim('(', ')', '|'))
            out += if (c != null) Part(c, text) else Part(null, text, raw = tok.second)
        }
        return out
    }

    /** ChordPro inline chords: "Tu[Am]jhe dekha [F]to" → parts. Non-chord brackets stay as text. */
    fun inline(line: String): List<Part>? {
        val m = Regex("""\[([^\[\]]{1,12})\]""").findAll(line).filter { TextChord.parse(it.groupValues[1]) != null }.toList()
        if (m.isEmpty()) return null
        val out = ArrayList<Part>()
        if (m.first().range.first > 0) out += Part(null, line.substring(0, m.first().range.first))
        for ((i, r) in m.withIndex()) {
            val end = m.getOrNull(i + 1)?.range?.first ?: line.length
            out += Part(TextChord.parse(r.groupValues[1]), line.substring(r.range.last + 1, end))
        }
        return out
    }

    fun parse(text: String): ParsedSheet {
        val src = clean(text).lines()
        var title: String? = null
        var artist: String? = null
        var key: String? = null
        var capo: Int? = null
        val out = ArrayList<SheetLine>()
        var inTab = false
        var pendingTime: Double? = null
        var i = 0
        while (i < src.size) {
            val raw = src[i].trimEnd()
            val t = raw.trim()
            i++
            if (inTab) {
                if (Regex("""^\{\s*(end_of_tab|eot)\s*\}$""").matches(t)) inTab = false else out += SheetLine.Tab(raw)
                continue
            }
            if (t.isEmpty()) { if (out.lastOrNull() != SheetLine.Blank && out.isNotEmpty()) out += SheetLine.Blank; continue }
            if (t.startsWith("#")) continue    // ChordPro comment
            DIRECTIVE.matchEntire(t)?.let { m ->
                val name = m.groupValues[1].lowercase()
                val v = m.groupValues[2].trim()
                when (name) {
                    "title", "t" -> title = v
                    "subtitle", "st", "artist" -> artist = v
                    "key" -> key = v
                    "capo" -> capo = v.toIntOrNull()
                    "comment", "c", "ci", "comment_italic", "cb", "comment_box", "highlight" -> out += SheetLine.Comment(v)
                    "start_of_chorus", "soc" -> out += SheetLine.Section(v.ifEmpty { "Chorus" })
                    "start_of_verse", "sov" -> out += SheetLine.Section(v.ifEmpty { "Verse" })
                    "start_of_bridge", "sob" -> out += SheetLine.Section(v.ifEmpty { "Bridge" })
                    "start_of_tab", "sot" -> { inTab = true; if (v.isNotEmpty()) out += SheetLine.Section(v) }
                    "chorus" -> out += SheetLine.Section(v.ifEmpty { "Chorus" })
                    "x_time" -> pendingTime = v.toDoubleOrNull()
                }
                return@let
            } ?: run {
                if (TAB_LINE.matches(raw)) { out += SheetLine.Tab(raw); return@run }
                sectionName(t)?.let { out += SheetLine.Section(it); return@run }
                // "Intro: Am G C" — a section label followed by chords on the same line
                Regex("""^([A-Za-z][A-Za-z\- ]{1,20}?\s*\d*)\s*:\s*(.+)$""").matchEntire(t)?.let { m ->
                    if (SECTION_WORDS.containsMatchIn(m.groupValues[1]) && chordLine(m.groupValues[2]) != null) {
                        out += SheetLine.Section(m.groupValues[1].trim())
                        out += SheetLine.Lyrics(chordPartsFromTokens(chordLine(m.groupValues[2])!!))
                        return@run
                    }
                }
                val chords = chordLine(raw)
                if (chords != null) {
                    // chords over the next line, if that's a lyric line
                    val next = src.getOrNull(i)?.let { clean(it).trimEnd() }
                    if (next != null && next.isNotBlank() && chordLine(next) == null && sectionName(next.trim()) == null &&
                        !TAB_LINE.matches(next) && !next.trim().startsWith("{")
                    ) {
                        out += SheetLine.Lyrics(merge(chords, next), pendingTime); pendingTime = null
                        i++
                    } else out += SheetLine.Lyrics(chordPartsFromTokens(chords))
                    return@run
                }
                out += SheetLine.Lyrics(inline(raw) ?: listOf(Part(null, raw)), pendingTime)
                pendingTime = null
            }
        }
        while (out.lastOrNull() == SheetLine.Blank) out.removeAt(out.size - 1)
        return ParsedSheet(title, artist, key, capo, out)
    }

    /** Whether shared text is a chord sheet (rather than a search or a link). */
    fun looksLikeSheet(text: String): Boolean {
        val lines = clean(text).lines().filter { it.isNotBlank() }
        if (lines.size < 3) return false
        val chordLines = lines.count { chordLine(it) != null }
        val inlineLines = lines.count { inline(it) != null && chordLine(it) == null }
        val directives = lines.count { DIRECTIVE.matches(it.trim()) }
        return chordLines >= 2 || inlineLines >= 2 || directives >= 2
    }

    /** A title to suggest for pasted text without {title}: the first short line that isn't chords. */
    fun guessTitle(text: String): String? = clean(text).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?.takeIf { it.length <= 60 && chordLine(it) == null && sectionName(it) == null && !it.startsWith("{") && inline(it) == null }
}
