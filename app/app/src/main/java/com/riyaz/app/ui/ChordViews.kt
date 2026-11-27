package com.riyaz.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riyaz.app.PlayerViewModel
import com.riyaz.app.analysis.Chord
import com.riyaz.app.analysis.Fingering
import com.riyaz.app.analysis.GuitarChords
import com.riyaz.app.analysis.SongAnalysis
import com.riyaz.app.audio.Song
import kotlin.math.max
import kotlin.math.roundToInt

/** How a chord from the analysis (original key) is shown for this song's key shift, capo and simplify. */
private class ChordView(song: Song, analysis: SongAnalysis) {
    private val shift = song.semitones - song.capo           // shapes = sounding chord minus capo
    private val simplify = song.simplifyChords
    val flats = analysis.key.transposed(shift).usesFlats
    fun shown(c: Chord): Chord = c.transposed(shift).let { if (simplify) it.simplified() else it }
    fun name(c: Chord) = shown(c).name(flats)
}

@Composable
fun ChordSection(vm: PlayerViewModel, song: Song, analysis: SongAnalysis?, analyzing: Float?, analysisError: String?,
                 makingSheet: Boolean = false, onSheet: (() -> Unit)? = null) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chords", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (analysis != null && onSheet != null) {
                    if (makingSheet) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else androidx.compose.material3.TextButton(onClick = onSheet) { Text("Chord sheet") }
                }
            }
            when {
                analysis != null -> ChordContent(vm, song, analysis)
                analysisError != null -> Text(analysisError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("Finding chords, key and tempo…", style = MaterialTheme.typography.bodyMedium)
                    val p = analyzing
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ChordContent(vm: PlayerViewModel, song: Song, analysis: SongAnalysis) {
    val view = ChordView(song, analysis)
    val positionMs by vm.positionMs.collectAsState()
    val sec = positionMs / 1000.0

    // key / tempo / capo / simplify
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val sounding = analysis.key.transposed(song.semitones)
        AssistChip(onClick = {}, label = { Text("Key ${sounding.shortName()}") })
        AssistChip(
            onClick = vm::cycleBpmFactor,
            label = { Text("${(analysis.bpm * song.bpmFactor).roundToInt()} BPM") },
        )
        FilterChip(selected = song.simplifyChords, onClick = { vm.setSimplifyChords(!song.simplifyChords) }, label = { Text("Simple") })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Capo", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { vm.setCapo(song.capo - 1) }, enabled = song.capo > 0) { Icon(Icons.Default.Remove, "Capo down") }
        Text(if (song.capo == 0) "none" else "fret ${song.capo}", style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = { vm.setCapo(song.capo + 1) }, enabled = song.capo < 9) { Icon(Icons.Default.Add, "Capo up") }
    }

    // now / next with diagrams
    val idx = analysis.chordAt(sec)
    val now = analysis.segments.getOrNull(idx)
    val next = analysis.segments.drop(max(0, idx + 1)).firstOrNull { !it.chord.isNone && it.chord != now?.chord }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        ChordCard(now?.chord?.let(view::shown), view.flats, big = true, label = "Now")
        ChordCard(next?.chord?.let(view::shown), view.flats, big = false, label = "Next")
    }

    ChordLane(analysis, view, sec, onSeek = { vm.seekTo((it * 1000).toLong()) })
    if (song.capo > 0) {
        Text(
            "Shapes for capo ${song.capo}. They sound as ${analysis.key.transposed(song.semitones).shortName()}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChordCard(chord: Chord?, flats: Boolean, big: Boolean, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val none = chord == null || chord.isNone
        Text(
            if (none) "–" else chord!!.name(flats),
            fontSize = if (big) 34.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                none -> MaterialTheme.colorScheme.onSurfaceVariant
                big -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.height(4.dp))
        val f = chord?.let(GuitarChords::fingering)
        if (f != null) ChordDiagram(f, if (big) 120.dp else 88.dp) else Spacer(Modifier.size(if (big) 120.dp else 88.dp))
    }
}

@Composable
fun ChordDiagram(f: Fingering, width: androidx.compose.ui.unit.Dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dot = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.size(width = width, height = width * 1.15f)) {
        val fretsShown = 5
        val left = size.width * 0.18f
        val right = size.width * 0.92f
        val top = size.height * 0.16f
        val bottom = size.height * 0.98f
        val sx = (right - left) / 5
        val sy = (bottom - top) / fretsShown
        val stroke = 1.5.dp.toPx()
        // nut or base fret label
        if (f.baseFret <= 1) {
            drawLine(ink, Offset(left, top), Offset(right, top), strokeWidth = 4.dp.toPx())
        } else {
            val t = measurer.measure("${f.baseFret}fr", TextStyle(color = ink, fontSize = 10.sp))
            drawText(t, topLeft = Offset(0f, top + sy / 2 - t.size.height / 2))
        }
        for (i in 0..fretsShown) drawLine(ink.copy(alpha = 0.6f), Offset(left, top + i * sy), Offset(right, top + i * sy), strokeWidth = stroke)
        for (s in 0 until 6) drawLine(ink.copy(alpha = 0.8f), Offset(left + s * sx, top), Offset(left + s * sx, bottom), strokeWidth = stroke)
        val base = if (f.baseFret <= 1) 1 else f.baseFret
        // barre
        f.barre?.let { b ->
            val strings = f.frets.indices.filter { f.frets[it] == b }
            if (strings.size >= 2) {
                val y = top + (b - base + 0.5f) * sy
                drawRoundRect(
                    dot, topLeft = Offset(left + strings.first() * sx - sx * 0.3f, y - sy * 0.28f),
                    size = Size((strings.last() - strings.first()) * sx + sx * 0.6f, sy * 0.56f),
                    cornerRadius = CornerRadius(sy * 0.28f),
                )
            }
        }
        for (s in 0 until 6) {
            val fr = f.frets[s]
            val x = left + s * sx
            when {
                fr < 0 -> {
                    val r = sx * 0.22f; val y = top - sy * 0.45f
                    drawLine(ink, Offset(x - r, y - r), Offset(x + r, y + r), strokeWidth = stroke)
                    drawLine(ink, Offset(x - r, y + r), Offset(x + r, y - r), strokeWidth = stroke)
                }
                fr == 0 -> drawCircle(ink, radius = sx * 0.22f, center = Offset(x, top - sy * 0.45f), style = Stroke(stroke))
                else -> drawCircle(dot, radius = sx * 0.32f, center = Offset(x, top + (fr - base + 0.5f) * sy))
            }
        }
    }
}

/** A strip of upcoming chords on a time axis, with beat ticks and bar lines. Tap a chord to jump to it. */
@Composable
private fun ChordLane(analysis: SongAnalysis, view: ChordView, sec: Double, onSeek: (Double) -> Unit) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val block = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    val before = 1.5
    val window = 10.0
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(analysis) {
                detectTapGestures { o ->
                    val t = sec - before + o.x / size.width * window
                    val i = analysis.chordAt(t)
                    if (i >= 0) onSeek(analysis.segments[i].start)
                }
            },
    ) {
        val pxPerSec = size.width / window.toFloat()
        val t0 = sec - before
        fun x(t: Double) = ((t - t0) * pxPerSec).toFloat()
        val rowTop = 14.dp.toPx()
        val rowH = size.height - rowTop
        // beats and bars
        analysis.beats.forEachIndexed { i, b ->
            val xx = x(b)
            if (xx < 0 || xx > size.width) return@forEachIndexed
            val bar = Math.floorMod(i - analysis.barPhase, 4) == 0
            drawLine(
                if (bar) ink.copy(alpha = 0.7f) else muted.copy(alpha = 0.35f),
                Offset(xx, if (bar) 0f else rowTop * 0.5f), Offset(xx, rowTop),
                strokeWidth = (if (bar) 2.dp else 1.dp).toPx(),
            )
        }
        // chord blocks
        for (s in analysis.segments) {
            if (s.end < t0 || s.start > t0 + window || s.chord.isNone) continue
            val a = max(0f, x(s.start)) + 1.dp.toPx()
            val b = minOf(size.width, x(s.end)) - 1.dp.toPx()
            if (b - a < 2) continue
            val playing = sec >= s.start && sec < s.end
            drawRoundRect(
                if (playing) active.copy(alpha = 0.25f) else block,
                topLeft = Offset(a, rowTop + 4.dp.toPx()), size = Size(b - a, rowH - 4.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            val txt = measurer.measure(
                view.name(s.chord),
                TextStyle(color = if (playing) active else ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            if (txt.size.width < b - a - 4) {
                drawText(txt, topLeft = Offset(a + 6.dp.toPx(), rowTop + (rowH - txt.size.height) / 2 + 2.dp.toPx()))
            }
        }
        // playhead
        val px = x(sec)
        drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
    }
}
