package com.riyaz.app.fit

import com.riyaz.app.analysis.Chord
import com.riyaz.app.analysis.ChordSegment
import com.riyaz.app.analysis.GuitarChords
import com.riyaz.app.voice.Notes
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max

/** What Riyaz understood about a song: shown as chips above the key suggestion. */
data class SongUnderstanding(
    val language: String?,          // "Hindi", "Telugu", "English" ... null if unsure
    val singer: String?,            // "male voice" / "female voice" / null
    val pace: String?,              // "slow" / "mid-tempo" / "upbeat"
    val rangeLow: Float?,           // the song's vocal range (MIDI) in the original key
    val rangeHigh: Float?,
    val center: Float?,
) {
    fun chips(): List<String> = listOfNotNull(language, singer, pace,
        if (rangeLow != null && rangeHigh != null) "sings ${Notes.name(rangeLow)}–${Notes.name(rangeHigh)}" else null)
}

object Understanding {
    private val TITLE_LANGUAGES = listOf(
        "telugu" to "Telugu", "hindi" to "Hindi", "bengali" to "Bengali", "bangla" to "Bengali", "tamil" to "Tamil",
        "punjabi" to "Punjabi", "kannada" to "Kannada", "malayalam" to "Malayalam", "marathi" to "Marathi",
        "bollywood" to "Hindi", "tollywood" to "Telugu",
    )

    // Common words in lyrics written in English letters (LRCLIB often has Indian songs romanised already).
    private val WORDS = mapOf(
        "Hindi" to setOf(
            "hai", "hain", "mein", "ki", "ka", "ke", "ko", "se", "tu", "tum", "tera", "teri", "tere", "mera", "meri", "mere",
            "dil", "pyaar", "pyar", "ishq", "jaan", "yaar", "kya", "nahi", "nahin", "hum", "jab", "kabhi", "bhi", "aur",
            "ye", "woh", "sanam", "zindagi", "khuda", "raat", "aankhon", "tujhe", "mujhe", "kar", "rahe", "tha", "thi",
        ),
        "Telugu" to setOf(
            "nenu", "nuvvu", "naa", "nee", "prema", "manasu", "ninnu", "nannu", "undi", "ledu", "kaadu", "ani", "emo",
            "chudu", "cheli", "kalalo", "gundello", "neelo", "naalo", "oka", "inka", "ela", "enduko", "nuvve", "nene",
        ),
        "Bengali" to setOf(
            "ami", "tumi", "amar", "tomar", "bhalobashi", "bhalobasha", "mon", "kotha", "ache", "achhe", "keno", "shudhu",
            "amake", "tomake", "hoye", "jabo", "aaj", "chokhe", "brishti", "kichu", "ekhon", "kobe", "bolo", "elo",
        ),
        "English" to setOf(
            "the", "and", "you", "your", "is", "are", "to", "of", "it", "on", "love", "baby", "we", "that", "with", "for",
            "all", "just", "know", "don't", "can", "when", "what", "my", "i'm", "like", "never", "want", "feel",
        ),
    )

    /** From the lyrics' script; for lyrics in English letters, from common words; else from the title. */
    fun language(lyrics: List<String>, title: String): String? {
        val text = lyrics.joinToString(" ")
        val scripts = HashMap<Character.UnicodeScript, Int>()
        for (ch in text + " " + title) if (ch.isLetter() && ch.code > 0x7F) {
            val s = Character.UnicodeScript.of(ch.code); scripts[s] = (scripts[s] ?: 0) + 1
        }
        scripts.maxByOrNull { it.value }?.takeIf { it.value >= 10 }?.let { (s, _) ->
            when (s) {
                Character.UnicodeScript.DEVANAGARI -> "Hindi"
                Character.UnicodeScript.TELUGU -> "Telugu"
                Character.UnicodeScript.TAMIL -> "Tamil"
                Character.UnicodeScript.BENGALI -> "Bengali"
                Character.UnicodeScript.GURMUKHI -> "Punjabi"
                Character.UnicodeScript.GUJARATI -> "Gujarati"
                Character.UnicodeScript.KANNADA -> "Kannada"
                Character.UnicodeScript.MALAYALAM -> "Malayalam"
                Character.UnicodeScript.ARABIC -> "Urdu"
                else -> null
            }?.let { return it }
        }
        val t = title.lowercase()
        TITLE_LANGUAGES.firstOrNull { (w, _) -> Regex("""\b$w\b""").containsMatchIn(t) }?.let { return it.second }
        val words = text.lowercase().split(Regex("""[^a-z']+""")).filter { it.isNotEmpty() }
        if (words.size < 8) return null
        val counts = WORDS.mapValues { (_, set) -> words.count { it in set } }
        val (best, n) = counts.maxByOrNull { it.value }!!
        val second = counts.filterKeys { it != best }.values.maxOrNull() ?: 0
        return if (n >= 4 && n >= 2 * second) best else null
    }

    /** Male / female lead, from where the melody sits (men's songs centre around G3-B3, women's around E4-A4). */
    fun singer(center: Float?): String? = when {
        center == null -> null
        center < 61f -> "male voice"
        center > 63f -> "female voice"
        else -> null
    }

    fun pace(bpm: Double?): String? = when {
        bpm == null || bpm <= 0 -> null
        bpm < 82 -> "slow"
        bpm < 112 -> "mid-tempo"
        else -> "upbeat"
    }
}

/**
 * A suggested key ([shift] semitones applied to the audio) and [capo], with the reason in words.
 * [base] is the fit before your habit was added: overrides are measured against it.
 */
data class KeySuggestion(
    val shift: Int,
    val base: Int,
    val octave: Int,                // the octave you'd sing it in relative to the singer (-1 = an octave below)
    val capo: Int,
    val reason: String,
    val learnedBias: Int,
    val sungLow: Float,             // the song's vocal range in the suggested key and your octave
    val sungHigh: Float,
)

/**
 * Picks the key that puts the song's vocal range inside your comfortable range, near where your voice is
 * happiest, without moving further from the original than needed; then adds your habit, if you have one.
 * Plain arithmetic, so every suggestion can say why.
 */
object KeyFit {
    fun suggest(
        songLow: Float, songHigh: Float, songCenter: Float,
        yourLow: Float, yourHigh: Float, yourCenter: Float,
        chords: List<ChordSegment>,
        learnedBias: Int = 0,
    ): KeySuggestion {
        // 1) the octave you'd sing it in: the one that puts the song's centre nearest yours
        val octave = (-1..1).minByOrNull { abs(songCenter + 12 * it - yourCenter) }!!
        val lo = songLow + 12 * octave
        val hi = songHigh + 12 * octave
        val c = songCenter + 12 * octave
        fun outside(s: Int) = max(0f, hi + s - yourHigh) + max(0f, yourLow - (lo + s))
        // 2) every shift from -7 to +7
        val base = (-7..7).minByOrNull { s ->
            val over = max(0f, hi + s - yourHigh)
            val under = max(0f, yourLow - (lo + s))
            3.0 * over * over + 3.0 * under * under +              // notes outside your range hurt most
                0.2 * (c + s - yourCenter) * (c + s - yourCenter) +  // sit where your voice is happiest
                0.3 * abs(s)                                         // don't move further than needed
        }!!
        // 3) your habit (e.g. "usually 1 lower than suggested"), unless it pushes the song well out of range
        val withHabit = (base + learnedBias).coerceIn(-7, 7)
        val shift = if (learnedBias != 0 && outside(withHabit) <= outside(base) + 1f) withHabit else base
        val capo = bestCapo(chords, shift)
        val top = hi + shift
        val reason = buildString {
            append(if (shift == 0) "The original key suits you" else "Key ${signed(shift)} suits you")
            append(": the tune goes up to ${Notes.name(top)}")
            append(when {
                top <= yourHigh + 0.5f -> ", within your comfortable top (${Notes.name(yourHigh)})"
                else -> ", a little above your comfortable top (${Notes.name(yourHigh)})"
            })
            if (lo + shift < yourLow - 0.5f) append(" and dips below your comfortable low (${Notes.name(yourLow)})")
            if (octave < 0) append(". You'd sing it an octave below the singer")
            if (octave > 0) append(". You'd sing it an octave above the singer")
            if (shift != base) append(". Includes your habit of going ${abs(shift - base)} ${if (shift < base) "lower" else "higher"}")
            if (capo > 0) append(". Capo $capo gives easier chord shapes")
            append(".")
        }
        return KeySuggestion(shift, base, octave, capo, reason, shift - base, lo + shift, top)
    }

    fun signed(s: Int) = if (s > 0) "+$s" else "$s"

    /** The capo fret (0-7) giving the easiest shapes for the song's chords in the new key. */
    fun bestCapo(chords: List<ChordSegment>, shift: Int): Int {
        val real = chords.filter { !it.chord.isNone }
        if (real.isEmpty()) return 0
        val total = real.sumOf { it.end - it.start }
        return (0..7).minByOrNull { capo ->
            real.sumOf { seg -> difficulty(seg.chord.transposed(shift - capo)) * (seg.end - seg.start) } / total +
                capo * 0.12   // all else equal, less capo
        }!!
    }

    /** 1 = open chord, 3+ = barre (higher up the neck is a bit harder); 7ths etc. add a little. */
    fun difficulty(c: Chord): Double {
        val f = GuitarChords.fingering(c) ?: return 5.0
        val open = f.baseFret <= 1 && f.barre == null
        val base = if (open) 1.0 else 3.0 + (f.baseFret - 1) * 0.1
        return base + if (c.quality == c.quality.simplified()) 0.0 else 0.3
    }
}

/** How you adjust suggested keys: (songId, suggested before habit, chosen), newest last. */
data class KeyHabits(val choices: List<Triple<String, Int, Int>> = emptyList()) {

    /** Your usual adjustment in semitones: the median of recent changes, 0 until there are at least three. */
    fun bias(): Int {
        val d = choices.takeLast(12).map { it.third - it.second }.filter { abs(it) <= 4 }
        if (d.size < 3) return 0
        return d.sorted()[d.size / 2]
    }

    fun record(songId: String, suggested: Int, chosen: Int): KeyHabits =
        KeyHabits((choices.filter { it.first != songId } + Triple(songId, suggested, chosen)).takeLast(50))

    fun toJson(): JSONArray = JSONArray(choices.map { JSONArray(listOf(it.first, it.second, it.third)) })

    companion object {
        fun fromJson(a: JSONArray?): KeyHabits = KeyHabits(
            if (a == null) emptyList() else List(a.length()) { i ->
                val e = a.getJSONArray(i); Triple(e.getString(0), e.getInt(1), e.getInt(2))
            },
        )
    }
}
