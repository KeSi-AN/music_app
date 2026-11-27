package com.riyaz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyaz.app.audio.SongLibrary
import com.riyaz.app.karaoke.VocalSeparator
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only: time vocal separation settings on the most recent song without touching its files.
 *   adb shell am broadcast -n com.riyaz.app/.SeparateTestReceiver --ez xnnpack true --ei threads 4 --ez arena true --ei chunks 4
 * Logs ms per chunk and peak memory (tag RiyazTest).
 */
class SeparateTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                val lib = SongLibrary(app)
                val song = lib.recent().first()
                val cfg = VocalSeparator.Config(
                    xnnpack = intent.getBooleanExtra("xnnpack", true),
                    threads = intent.getIntExtra("threads", 4),
                    arena = intent.getBooleanExtra("arena", true),
                    maxChunks = intent.getIntExtra("chunks", 4),
                )
                val t0 = System.nanoTime()
                VocalSeparator(app).separate(lib.pcmFile(song), song.sampleRate, File(app.cacheDir, "sep-test.pcm"), {}, { false }, cfg)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val hwm = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM") }?.trim()
                Log.i("RiyazTest", "separate $cfg: ${ms} ms total, ${ms / cfg.maxChunks} ms/chunk incl. setup; $hwm")
            } catch (e: Throwable) {
                Log.e("RiyazTest", "separate test failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
