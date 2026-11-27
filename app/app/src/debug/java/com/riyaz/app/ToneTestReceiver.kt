package com.riyaz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Debug-only: plays a test tone on the speaker so the tuner / range test can be checked by ear-less
 * testing. A slide from "hz" to "to" (log scale) when "to" is given; harmonics make it voice-like.
 *   adb shell am broadcast -n com.riyaz.app/.ToneTestReceiver --ef hz 220 --ef secs 3 [--ef to 110] [--ei harmonics 6]
 */
class ToneTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hz = intent.getFloatExtra("hz", 440f).toDouble()
        val to = intent.getFloatExtra("to", hz.toFloat()).toDouble()
        val secs = intent.getFloatExtra("secs", 2f).toDouble()
        val harmonics = intent.getIntExtra("harmonics", 4)
        val pending = goAsync()
        thread {
            try {
                val sr = 48000
                val n = (secs * sr).toInt()
                val pcm = ShortArray(n)
                var phase = 0.0
                for (i in 0 until n) {
                    val f = hz * exp(ln(to / hz) * i / n)
                    phase += 2 * PI * f / sr
                    var v = 0.0
                    for (h in 1..harmonics) v += sin(phase * h) / h
                    val env = minOf(1.0, i / 2000.0, (n - i) / 2000.0)
                    pcm[i] = (v * 0.35 * env * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build()
                track.write(pcm, 0, n)
                track.play()
                Thread.sleep((secs * 1000).toLong() + 200)
                track.release()
            } finally {
                pending.finish()
            }
        }
    }
}
