package com.riyaz.app.lyrics

/**
 * Writes lyrics in plain English letters, the way people type them ("uska hi bana", "andamaina prema
 * lekha"). Sound for sound, not translated.
 *
 * Hindi (Devanagari) uses rules here: everyday spellings plus schwa deletion, because Hindi drops most
 * written "a" sounds (उसका is "uska", not "usakaa"). Other Indian scripts and Urdu go through ICU's
 * transliterator ([icuLatin], Android's android.icu in the app) and are then reduced to plain letters.
 */
class Romanizer(private val icuLatin: (String) -> String) {

    fun romanize(line: String): String {
        if (line.none { it.code > 0x7F }) return line
        val sb = StringBuilder()
        // split into words and the gaps between them, so each word can be handled by its script
        val re = Regex("""[\p{L}\p{M}\p{N}‌‍]+""")
        var last = 0
        for (m in re.findAll(line)) {
            sb.append(punct(line.substring(last, m.range.first)))
            sb.append(word(m.value))
            last = m.range.last + 1
        }
        sb.append(punct(line.substring(last)))
        val out = sb.toString().replace(Regex("""\s+"""), " ").trim()
        return out.replaceFirstChar { it.uppercaseChar() }
    }

    private fun punct(s: String) = s.replace('।', '.').replace("॥", ".")

    private fun word(w: String): String {
        val script = w.firstOrNull { it.code > 0x7F }?.let { Character.UnicodeScript.of(it.code) } ?: return w
        return when (script) {
            Character.UnicodeScript.DEVANAGARI -> Devanagari.word(w)
            else -> {
                val latin = plain(icuLatin(w))
                // these languages also drop the final written "a" (Bengali আমার = "amar", Punjabi ਦਿਲ = "dil")
                if (script == Character.UnicodeScript.BENGALI || script == Character.UnicodeScript.GURMUKHI ||
                    script == Character.UnicodeScript.GUJARATI || script == Character.UnicodeScript.ORIYA
                ) dropFinalA(latin) else latin
            }
        }
    }

    /** ISO-15919 style output from ICU to everyday spelling, then plain ASCII. */
    private fun plain(s: String): String {
        var t = s.lowercase()
        t = t.replace("ch", "\u0001")          // ICU: छ = "ch", च = "c"
            .replace("c", "ch").replace("\u0001", "chh")
            .replace("ś", "sh").replace("ṣ", "sh")
            .replace(Regex("""[ṁṃ](?=[pbm])"""), "m").replace(Regex("""[ṁṃ]$"""), "m")   // Telugu కాలం "kalam"
            .replace(Regex("""[ṁṃ]"""), "n").replace("m̐", "n")
            .replace("ṅ", "n").replace("ñ", "n").replace("ṇ", "n")
            .replace("r̥", "ri").replace("ṛ", "r").replace("ṟ", "r").replace("ḻ", "zh").replace("ḷ", "l")
            .replace("ṭ", "t").replace("ḍ", "d").replace("ḥ", "h").replace("ẏ", "y")
            .replace("ā", "a").replace("ī", "i").replace("ū", "u").replace("ē", "e").replace("ō", "o")
        // anything else with marks: strip the marks
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return t.filter { it.code < 0x80 }
    }

    private fun dropFinalA(w: String) =
        if (w.length > 2 && w.endsWith("a") && w[w.length - 2] !in "aeiou") w.dropLast(1) else w
}

/** The app's romanizer, backed by Android's ICU transliterator (built once; it's slow to create). */
object Romanization {
    val instance: Romanizer by lazy {
        val icu = android.icu.text.Transliterator.getInstance("Any-Latin")
        Romanizer { icu.transliterate(it) }
    }
}

/** Hindi in Devanagari to everyday Roman spelling with schwa deletion. */
internal object Devanagari {
    private val COMMON = mapOf(
        "में" to "mein", "हैं" to "hain", "मैं" to "main", "है" to "hai", "नहीं" to "nahi", "नहि" to "nahi",
        "वो" to "wo", "वह" to "woh", "यह" to "yeh", "ये" to "ye", "क्यों" to "kyun", "यूँ" to "yun", "यूं" to "yun",
        "कहाँ" to "kahan", "यहाँ" to "yahan", "वहाँ" to "wahan", "हूँ" to "hoon", "हूं" to "hoon", "तू" to "tu",
        "भी" to "bhi", "ही" to "hi", "की" to "ki", "जी" to "ji",
        "ऐ" to "ae", "ए" to "ae",
    )

    private val CONS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        // precomposed nukta letters (Urdu sounds): क़ ख़ ग़ ज़ ड़ ढ़ फ़ य़
        'क़' to "q", 'ख़' to "kh", 'ग़' to "gh", 'ज़' to "z", 'ड़' to "r", 'ढ़' to "rh", 'फ़' to "f", 'य़' to "y",
    )
    private val NUKTA = mapOf("k" to "q", "kh" to "kh", "g" to "gh", "j" to "z", "d" to "r", "dh" to "rh", "ph" to "f", "y" to "y")

    private val INDEPENDENT = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val MATRA = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )

    private class Unit(var cons: String?, var vowel: String?, var inherent: Boolean, var coda: String = "")

    fun word(w: String): String {
        COMMON[w]?.let { return it }
        val units = ArrayList<Unit>()
        for (ch in w) {
            when {
                ch in CONS -> units += Unit(CONS.getValue(ch), "a", inherent = true)
                ch == '़' -> units.lastOrNull()?.let { u -> u.cons = NUKTA[u.cons] ?: u.cons }
                ch in MATRA -> units.lastOrNull()?.let { it.vowel = MATRA.getValue(ch); it.inherent = false }
                ch == '्' -> units.lastOrNull()?.let { it.vowel = null; it.inherent = false }
                ch == 'ं' || ch == 'ँ' -> units.lastOrNull()?.let { it.coda += "N" } ?: run { units += Unit(null, "", false, "N") }
                ch == 'ः' -> units.lastOrNull()?.let { it.coda += "h" }
                ch in INDEPENDENT -> units += Unit(null, INDEPENDENT.getValue(ch), inherent = false)
                ch.isDigit() || ch.code < 0x80 -> units += Unit(ch.toString(), "", false)
                else -> {}   // zero-width joiners, unknown signs
            }
        }
        // ज्ञ is said "gy"
        for (i in 0 until units.size - 1) {
            if (units[i].cons == "j" && units[i].vowel == null && units[i + 1].cons == "n" && w.contains("ज्ञ")) {
                units[i].cons = "g"; units[i + 1].cons = "y"
            }
        }
        // 1) a word-final inherent "a" is silent (except one-letter words like न "na")
        units.lastOrNull()?.let { if (it.inherent && it.cons != null && units.size > 1 && it.coda.isEmpty()) it.vowel = null }
        // 2) V C a C V: the middle "a" is silent (उसका -> uska), scanning right to left
        for (i in units.size - 2 downTo 1) {
            val u = units[i]
            val prev = units[i - 1]
            val next = units[i + 1]
            if (u.inherent && u.cons != null && u.coda.isEmpty() &&
                prev.vowel != null && prev.coda.isEmpty() &&
                next.cons != null && next.vowel != null
            ) u.vowel = null
        }
        val vowelsInWord = units.count { it.vowel != null && it.vowel != "" }
        val sb = StringBuilder()
        for ((i, u) in units.withIndex()) {
            u.cons?.let { sb.append(it) }
            var v = u.vowel ?: ""
            // one-vowel words ending in a consonant keep doubled vowels: चाँद "chaand", बाद "baad", जीत "jeet"
            val closedMono = vowelsInWord == 1 && i < units.lastIndex
            // everyday spelling: long vowels written short except where people write them doubled
            when (v) {
                "aa" -> if (!(i == 0 && u.cons == null) && !closedMono) v = "a"
                "ee" -> if (!closedMono) v = "i"
                "oo" -> if (!closedMono) v = "u"
            }
            sb.append(v)
            for (c in u.coda) {
                if (c == 'N') {
                    val nextCons = units.getOrNull(i + 1)?.cons ?: ""
                    sb.append(if (nextCons.startsWith("p") || nextCons.startsWith("b") || nextCons.startsWith("m")) "m" else "n")
                } else sb.append(c)
            }
        }
        return sb.toString()
    }
}
