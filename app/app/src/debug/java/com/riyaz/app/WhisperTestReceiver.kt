package com.riyaz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyaz.app.audio.SongLibrary
import com.riyaz.app.karaoke.VocalSeparator
import com.riyaz.app.lyrics.LyricsAligner
import com.riyaz.app.lyrics.LyricsStore
import com.riyaz.app.lyrics.Romanization
import com.riyaz.app.lyrics.WhisperLyrics
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Debug-only: Whisper accuracy and speed on a song whose lyrics are already synced. Runs Whisper on the
 * vocal track, aligns the lyrics with their times removed, and compares with the real times. Writes
 * only to the cache folder; the song's own files are untouched.
 *   adb shell am broadcast -n com.riyaz.app/.WhisperTestReceiver --es id 5dcf520967de5f88 --es lang te
 * (run it with the app on screen: in the background Android schedules it on the slow cores)
 */
class WhisperTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val id = intent.getStringExtra("id") ?: "5dcf520967de5f88"
        val lang = intent.getStringExtra("lang")
        val threads = intent.getIntExtra("threads", WhisperLyrics.DEFAULT_THREADS)
        thread(priority = Thread.MAX_PRIORITY) {
            try {
                val lib = SongLibrary(app)
                val song = lib.recent().first { it.id == id }
                val dir = lib.dir(song)
                val w = WhisperLyrics(app)
                if (!w.ready) {
                    var last = -1
                    w.ensureModels({ p -> val pc = (p * 100).toInt(); if (pc / 10 != last / 10) { last = pc; Log.i(T, "download $pc%") } }, { false })
                }
                val t0 = System.currentTimeMillis()
                val heard = w.hear(lib.pcmFile(song), VocalSeparator.backingFile(dir).takeIf { it.exists() }, song.sampleRate, lang, threads)
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                if (heard == null) { Log.e(T, "whisper failed"); return@thread }
                File(app.cacheDir, "heard_$id.json").let { WhisperLyrics.save(it.parentFile!!, heard); WhisperLyrics.file(it.parentFile!!).renameTo(it) }
                Log.i(T, "heard ${heard.size} segments ($threads threads) in ${"%.0f".format(secs)} s for a ${song.durationMs / 1000} s song")
                heard.take(8).forEach { Log.i(T, "  %6.1f-%6.1f %s".format(it.start, it.end, Romanization.instance.romanize(it.text))) }
                val lyrics = LyricsStore.load(dir)
                if (lyrics == null || !lyrics.synced) { Log.i(T, "no synced lyrics to compare with"); return@thread }
                val r = Romanization.instance
                val res = LyricsAligner.align(lyrics.lines.map { r.romanize(it.text) }, heard.map { it.copy(text = r.romanize(it.text)) })
                if (res == null) { Log.i(T, "aligner: couldn't match"); return@thread }
                val err = lyrics.lines.indices.map { abs(res.times[it] - lyrics.lines[it].time!!) }
                val sorted = err.sorted()
                Log.i(T, "aligned ${lyrics.lines.size} lines, ${"%.0f".format(res.matched * 100)}% letters matched: median error " +
                    "${"%.2f".format(sorted[sorted.size / 2])} s, within 1 s ${err.count { it <= 1.0 } * 100 / err.size}%, " +
                    "within 2 s ${err.count { it <= 2.0 } * 100 / err.size}%, worst ${"%.1f".format(sorted.last())} s")
                lyrics.lines.indices.filter { err[it] > 2.0 }.take(6).forEach {
                    Log.i(T, "  off: line $it real ${lyrics.lines[it].time} got ${"%.1f".format(res.times[it])}  ${r.romanize(lyrics.lines[it].text)}")
                }
            } catch (e: Throwable) {
                Log.e(T, "whisper test failed", e)
            } finally {
                Log.i(T, "whisper test done")
                pending.finish()
            }
        }
    }

    companion object { private const val T = "RiyazTest" }
}
