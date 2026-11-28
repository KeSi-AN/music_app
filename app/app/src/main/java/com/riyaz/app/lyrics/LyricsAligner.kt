package com.riyaz.app.lyrics

/** A stretch of heard text with its time in the song (a Whisper segment or word). */
data class TimedText(val start: Double, val end: Double, val text: String)

/**
 * Puts times on lyrics that came without them, from what Whisper heard in the vocal track. Whisper
 * often gets sung words wrong, so instead of trusting its words, both texts are reduced to a rough
 * sound-alike spelling and lined up letter by letter (edit-distance alignment, in order, so repeated
 * choruses land on the right repeat). Each line gets the time of the first of its letters that matched.
 */
object LyricsAligner {
    const val WEAK_LINE = 0.5f
    const val MAX_WEAK_SHARE = 0.5f   // measured: a good sync (WHITE KEYS) had 29% weak lines, a cover singing other verses 69%

    /** [lineMatch]: share of each line's letters found in the singing (low = probably not sung there). */
    data class Result(val times: List<Double>, val matched: Float, val lineMatch: List<Float> = emptyList())

    /**
     * Sound-alike key: romanised, lower-case letters only, with the spellings people mix up merged
     * (pyaar/pyar, khuda/kuda, zindagi/jindagi, mujhe/muje, vo/wo).
     */
    fun key(romanised: String): String {
        var s = romanised.lowercase().filter { it in 'a'..'z' }
        for ((a, b) in listOf("aa" to "a", "ee" to "i", "ii" to "i", "oo" to "u", "uu" to "u", "kh" to "k", "gh" to "g",
            "bh" to "b", "dh" to "d", "th" to "t", "ph" to "f", "jh" to "j", "sh" to "s", "ch" to "c", "w" to "v", "z" to "j",
            "q" to "k", "y" to "i", "h" to "", "e" to "i", "o" to "u")) s = s.replace(a, b)
        return s.replace(Regex("(.)\\1+"), "$1")
    }

    /**
     * [lines]: the lyrics, romanised. [heard]: what Whisper heard, romanised, with times. Returns a start
     * time for every line, or null when too little of the lyrics could be found in the recording.
     */
    fun align(lines: List<String>, heard: List<TimedText>, minMatched: Float = 0.35f, maxWeakShare: Float = MAX_WEAK_SHARE): Result? {
        val a = StringBuilder()
        val lineOf = ArrayList<Int>()
        lines.forEachIndexed { i, l -> key(l).forEach { a.append(it); lineOf += i } }
        val b = StringBuilder()
        val timeOf = ArrayList<Double>()
        for (t in heard) {
            val k = key(t.text)
            k.forEachIndexed { j, c -> b.append(c); timeOf += t.start + (t.end - t.start) * j / k.length.coerceAtLeast(1) }
        }
        val n = a.length
        val m = b.length
        if (n == 0 || m == 0 || n.toLong() * m > 30_000_000L) return null

        // semi-global alignment: skipping heard letters before and after the lyrics is free
        val back = ByteArray((n + 1) * (m + 1))     // 0 diagonal, 1 up (lyric letter unheard), 2 left (extra heard letter)
        var prev = IntArray(m + 1)                  // row 0: all zero (free start in b)
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            back[i * (m + 1)] = 1
            val ai = a[i - 1]
            for (j in 1..m) {
                val diag = prev[j - 1] + if (ai == b[j - 1]) 0 else 1
                val up = prev[j] + 1
                val left = cur[j - 1] + 1
                var best = diag; var dir = 0
                if (up < best) { best = up; dir = 1 }
                if (left < best) { best = left; dir = 2 }
                cur[j] = best
                back[i * (m + 1) + j] = dir.toByte()
            }
            val t = prev; prev = cur; cur = t
        }
        // free end in b: finish wherever the last lyric letter is cheapest
        var j = (0..m).minByOrNull { prev[it] }!!
        var i = n
        val hitTime = arrayOfNulls<Double>(n)       // time of the heard letter each lyric letter matched
        var matched = 0
        while (i > 0 && j > 0) {
            when (back[i * (m + 1) + j].toInt()) {
                0 -> {
                    if (a[i - 1] == b[j - 1]) { matched++; hitTime[i - 1] = timeOf[j - 1] }
                    i--; j--
                }
                1 -> i--
                else -> j--
            }
        }
        val ratio = matched.toFloat() / n
        if (ratio < minMatched) return null

        val lineLetters = IntArray(lines.size)
        val lineHits = IntArray(lines.size)
        for (p in 0 until n) { lineLetters[lineOf[p]]++; if (hitTime[p] != null) lineHits[lineOf[p]]++ }
        val lineMatch = lines.indices.map { if (lineLetters[it] == 0) 1f else lineHits[it].toFloat() / lineLetters[it] }
        // When the recording sings verses the lyrics don't have, or in another order (common in covers),
        // in-order alignment stretches lines over the wrong singing; they then match only patchily. Better
        // to say so than to show wrong timings.
        if (lineMatch.count { it < WEAK_LINE } > lines.size * maxWeakShare) return null

        // a line's time: its first run of 3 matched letters (stray single letters can match anywhere)
        val firstHit = arrayOfNulls<Double>(lines.size)
        for (p in 0 until n) {
            val line = lineOf[p]
            if (firstHit[line] != null) continue
            val run = (0 until 3).all { d -> p + d < n && lineOf[p + d] == line && hitTime[p + d] != null }
            if (run) firstHit[line] = hitTime[p]
        }

        // lines nothing matched in: share out the time between their neighbours by length
        val times = DoubleArray(lines.size) { firstHit[it] ?: Double.NaN }
        for (k in times.indices) if (!times[k].isNaN() && k > 0) {
            val before = (k - 1 downTo 0).firstOrNull { !times[it].isNaN() }
            if (before != null && times[k] < times[before]) times[k] = Double.NaN   // out of order: don't trust it
        }
        fun len(idx: Int) = key(lines[idx]).length.coerceAtLeast(1).toDouble()
        var k = 0
        while (k < times.size) {
            if (!times[k].isNaN()) { k++; continue }
            val s = k
            while (k < times.size && times[k].isNaN()) k++
            // the stretch from the last known line start to the next one holds lines from..k-1, each taking
            // time in proportion to its length
            val from = if (s > 0) s - 1 else 0
            val t0 = if (s > 0) times[s - 1] else heard.first().start
            val t1 = if (k < times.size) times[k] else heard.last().end
            val total = (from until k).sumOf { len(it) }
            var acc = (from until s).sumOf { len(it) }
            for (idx in s until k) {
                times[idx] = t0 + (t1 - t0) * acc / total
                acc += len(idx)
            }
        }
        return Result(times.toList(), ratio, lineMatch)
    }
}
