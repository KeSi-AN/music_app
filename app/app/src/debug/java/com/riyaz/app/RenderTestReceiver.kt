package com.riyaz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyaz.app.audio.NativeEngine
import com.riyaz.app.audio.SongLibrary
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only test hook. Example:
 * adb shell am broadcast -n com.riyaz.app/.RenderTestReceiver --ef start 60 --ef secs 20 \
 *     --ef semis -3 --ef tempo 1 --ez natural false --es name k-3
 * Writes Android/data/com.riyaz.app/files/render/<name>.wav for the most recent song.
 */
class RenderTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                val lib = SongLibrary(app)
                val song = lib.recent().first()
                val dir = File(app.getExternalFilesDir(null), "render").apply { mkdirs() }
                val name = intent.getStringExtra("name") ?: "render"
                val start = intent.getFloatExtra("start", 0f)
                val secs = intent.getFloatExtra("secs", 20f)
                val out = File(dir, "$name.wav")
                val t0 = System.nanoTime()
                val ok = NativeEngine.nativeRenderTest(
                    lib.pcmFile(song).absolutePath, song.sampleRate,
                    (start * song.sampleRate).toLong(), (secs * song.sampleRate).toInt(),
                    intent.getFloatExtra("semis", 0f), intent.getFloatExtra("tempo", 1f),
                    intent.getBooleanExtra("natural", false), out.absolutePath,
                )
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i("RiyazTest", "render $name ok=$ok song=${song.title} sr=${song.sampleRate} took=${ms}ms -> $out")
            } catch (e: Throwable) {
                Log.e("RiyazTest", "render failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
