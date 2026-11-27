package com.riyaz.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.riyaz.app.KaraokeState
import com.riyaz.app.PlayerViewModel
import com.riyaz.app.audio.Song
import com.riyaz.app.voice.Melody
import com.riyaz.app.voice.Notes
import com.riyaz.app.voice.SessionResult
import com.riyaz.app.voice.SungFrame
import kotlin.math.abs
import kotlin.math.roundToInt

/** Sing along with the mic: your voice drawn over the song's melody, and learning from it. */
@Composable
fun SingPanel(vm: PlayerViewModel, song: Song, k: KaraokeState) {
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.startSinging() }
    val start = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startSinging()
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }
    k.lastSession?.let { SessionCard(it, k.lastSessionSaved, k.learn, vm::dismissSessionResult) }
    when {
        k.melody == null && k.melodyProgress != null -> {
            Text("Getting the song's melody ready…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { k.melodyProgress }, modifier = Modifier.fillMaxWidth())
        }
        k.melody == null -> {}   // needs the karaoke track first
        !k.singing -> Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = start) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Sing")
            }
            Spacer(Modifier.weight(1f))
            Text("Learn from it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Switch(checked = k.learn, onCheckedChange = vm::setLearnFromSession)
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = vm::stopSinging) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Done singing")
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (k.headphones) "Listening…" else "Use headphones: the mic hears the speaker too",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (k.headphones) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            SingLane(vm, song, k.melody)
        }
    }
}

/** The melody (note bars, in your key) with your voice on top, 3 s back and 3 s ahead. */
@Composable
private fun SingLane(vm: PlayerViewModel, song: Song, melody: Melody) {
    val positionMs by vm.positionMs.collectAsState()
    val sung by vm.liveSinging.collectAsState()
    val delay by vm.liveDelay.collectAsState()
    val measurer = rememberTextMeasurer()
    val bar = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val good = Color(0xFF7BD88F)
    val off = MaterialTheme.colorScheme.primary
    val key = song.semitones + song.cents / 100f
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val now = positionMs / 1000.0
        val t0 = now - 3.0
        val t1 = now + 3.0
        fun x(t: Double) = ((t - t0) / (t1 - t0) * size.width).toFloat()
        // which octave you're singing in (you may sing a song an octave down)
        val octave = sung.takeLast(120).mapNotNull { f -> melody.at(f.songSec - delay).takeIf { !it.isNaN() }?.let { ((f.midi - it - key) / 12f).roundToInt() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
        val shift = key + 12 * octave
        // vertical range around what's on screen
        val visible = ArrayList<Float>()
        var t = t0
        while (t < t1) { melody.at(t).takeIf { !it.isNaN() }?.let { visible += it + shift }; t += 0.1 }
        sung.forEach { if (it.songSec - delay in t0..t1) visible += it.midi }
        val center = visible.sorted().getOrNull(visible.size / 2) ?: 57f
        val lo = center - 7f
        val hi = center + 7f
        fun y(m: Float) = size.height - ((m - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
        // note names on the left, faint lines on C and A
        for (m in lo.toInt()..hi.toInt()) if (Math.floorMod(m, 12) == 0 || Math.floorMod(m, 12) == 9) {
            drawLine(ink.copy(alpha = 0.2f), Offset(0f, y(m.toFloat())), Offset(size.width, y(m.toFloat())), 1f)
            drawText(measurer.measure(Notes.name(m), TextStyle(color = ink, fontSize = 9.sp)), topLeft = Offset(2f, y(m.toFloat()) - 13f))
        }
        // melody bars
        val noteH = size.height / (hi - lo) * 0.8f
        for (n in melody.notes()) {
            if (n.end < t0 || n.start > t1) continue
            val m = n.midi + shift
            drawRoundRect(bar, Offset(x(n.start), y(m) - noteH / 2), Size(x(n.end) - x(n.start), noteH), CornerRadius(noteH / 2))
        }
        // your voice: green when within half a semitone of the melody, orange otherwise
        var prev: Offset? = null
        var prevT = 0.0
        for (f in sung) {
            val st = f.songSec - delay           // where in the song you were singing
            if (st < t0) continue
            val target = melody.at(st) + shift
            val p = Offset(x(st), y(f.midi))
            val col = if (!target.isNaN() && abs(f.midi - target) <= 0.5f) good else off
            prev?.let { if (f.songSec - prevT < 0.1) drawLine(col, it, p, strokeWidth = 5f, cap = StrokeCap.Round) }
            prev = p; prevT = f.songSec
        }
        drawLine(ink, Offset(x(now), 0f), Offset(x(now), size.height), strokeWidth = 2f)
    }
}

@Composable
private fun SessionCard(r: SessionResult, saved: Boolean, learn: Boolean, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!r.enough) {
                Text("Not enough singing to learn from. Sing along for at least a verse.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${(r.accuracy * 100).roundToInt()}% of notes in tune", style = MaterialTheme.typography.titleMedium)
                val parts = buildList {
                    add("${r.sungNotes.size} of ${r.notes.size} notes sung")
                    if (r.octave != 0) add("an octave ${if (r.octave < 0) "below" else "above"} the singer")
                    r.highestComfortable?.let { add("comfortable up to ${Notes.name(it)}") }
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (r.strainedNotes.isNotEmpty()) {
                    Text("Strained on ${r.strainedNotes.joinToString(", ") { Notes.name(it) }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    when {
                        saved -> "Added to your voice profile."
                        !learn -> "Not added to your voice profile (learning was off)."
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
