package com.riyaz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyaz.app.analysis.AnalysisStore
import com.riyaz.app.audio.SongLibrary
import com.riyaz.app.fit.KeyFit
import com.riyaz.app.fit.SongUnderstanding
import com.riyaz.app.fit.Understanding
import com.riyaz.app.lyrics.LyricsStore
import com.riyaz.app.voice.Melody
import com.riyaz.app.voice.Notes

/**
 * Debug-only, read-only: logs what auto-fit would suggest for every song in the library that has a
 * melody, for a range given on the command line (MIDI), without touching the voice profile or songs.
 *   adb shell am broadcast -n com.riyaz.app/.FitTestReceiver --ef low 45 --ef high 64 --ef center 57 --ei bias 0
 */
class FitTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val low = intent.getFloatExtra("low", 45f)
        val high = intent.getFloatExtra("high", 64f)
        val center = intent.getFloatExtra("center", (low + high) / 2f)
        val bias = intent.getIntExtra("bias", 0)
        val lib = SongLibrary(context.applicationContext)
        Log.i("RiyazTest", "fit for range ${Notes.name(low)}-${Notes.name(high)} centre ${Notes.name(center)} bias $bias")
        for (song in lib.recent()) {
            val dir = lib.dir(song)
            val m = Melody.load(dir)
            val a = AnalysisStore.load(dir)
            val lyrics = LyricsStore.load(dir)
            val r = m?.range()
            val und = SongUnderstanding(Understanding.language(lyrics?.lines?.map { it.text } ?: emptyList(), song.title),
                Understanding.singer(r?.third), Understanding.pace(a?.bpm?.times(song.bpmFactor)), r?.first, r?.second, r?.third)
            if (r == null) { Log.i("RiyazTest", "  '${song.title}': ${und.chips()} (no melody yet)"); continue }
            val s = KeyFit.suggest(r.first, r.second, r.third, low, high, center, a?.segments ?: emptyList(), bias)
            Log.i("RiyazTest", "  '${song.title}' key ${a?.key?.name()} now ${song.semitones}/capo ${song.capo}: ${und.chips()} -> " +
                "key ${s.shift} capo ${s.capo} octave ${s.octave} | ${s.reason}")
        }
        Log.i("RiyazTest", "fit done")
    }
}
