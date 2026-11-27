package com.riyaz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyaz.app.analysis.AnalysisStore
import com.riyaz.app.analysis.SongAnalyzer
import com.riyaz.app.audio.SongLibrary
import com.riyaz.app.youtube.YouTube
import kotlin.concurrent.thread

/**
 * Debug-only: analyse a song in the background, without opening the app's screen.
 *   adb shell am broadcast -n com.riyaz.app/.AnalyzeTestReceiver --es url https://youtu.be/XcHvWQwMXFM
 * Downloads + decodes the video if it isn't in the library yet, analyses it, writes analysis.json,
 * and logs timings (tag RiyazTest). Without "url" it analyses the most recent song.
 */
class AnalyzeTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val url = intent.getStringExtra("url")
        val force = intent.getBooleanExtra("force", false)
        thread {
            try {
                val lib = SongLibrary(app)
                val song = if (url != null) {
                    val id = lib.youtubeId(YouTube.videoId(url) ?: error("bad url"))
                    lib.cached(id) ?: run {
                        val dl = YouTube.downloadAudio(url, lib.folderFor(id)) {}
                        lib.importDownloaded(id, dl.file, dl.title, dl.channel, dl.url, dl.thumbnailUrl) {}
                    }
                } else lib.recent().first()
                val dir = lib.dir(song)
                if (!force && AnalysisStore.load(dir) != null) {
                    Log.i("RiyazTest", "analysis already there for ${song.id}")
                } else {
                    val t0 = System.nanoTime()
                    val a = SongAnalyzer(app).analyze(lib.pcmFile(song).absolutePath, song.sampleRate) {}
                    AnalysisStore.save(dir, a)
                    Log.i("RiyazTest", "analyzed ${song.id} '${song.title}' in ${(System.nanoTime() - t0) / 1_000_000} ms: " +
                        "${a.segments.size} chords, key ${a.key.name()}, ${"%.1f".format(a.bpm)} BPM, ${a.beats.size} beats")
                }
                Log.i("RiyazTest", "done ${song.id}")
            } catch (e: Throwable) {
                Log.e("RiyazTest", "analyze failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
