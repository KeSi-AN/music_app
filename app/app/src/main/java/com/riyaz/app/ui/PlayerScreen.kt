package com.riyaz.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riyaz.app.PlayerViewModel
import com.riyaz.app.audio.Song
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel) {
    val s by vm.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val openFile = { picker.launch(arrayOf("audio/*", "video/*")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Riyaz", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = openFile, enabled = !s.decoding) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open a song")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            s.error?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = vm::dismissError) { Text("OK") }
                    }
                }
            }

            if (s.decoding) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Preparing song…", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { s.decodeProgress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            val song = s.song
            if (song == null) {
                EmptyState(onOpen = openFile, enabled = !s.decoding)
            } else {
                SongHeader(song)
                Waveform(
                    peaks = s.peaks,
                    progress = if (song.durationMs > 0) s.positionMs.toFloat() / song.durationMs else 0f,
                    onSeek = { f -> vm.seekTo((f * song.durationMs).toLong()) },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(fmt(s.positionMs), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(fmt(song.durationMs), style = MaterialTheme.typography.labelMedium)
                }
                Transport(playing = s.playing, onToggle = vm::togglePlay, onBack = { vm.skip(-5000) }, onFwd = { vm.skip(5000) })
                KeyCard(song, vm)
                SpeedCard(song, vm)
            }

            val others = s.recent.filter { it.id != song?.id }
            if (others.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                others.forEach { r -> RecentRow(r) { vm.openRecent(r) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit, enabled: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Play any song in your key", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open an audio or video file, or share one to Riyaz from WhatsApp or your file manager.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpen, enabled = enabled) { Text("Open a song") }
    }
}

@Composable
private fun SongHeader(song: Song) {
    Column {
        Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        song.artist?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Waveform(peaks: FloatArray?, progress: Float, onSeek: (Float) -> Unit) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val shown = dragFrac ?: progress
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.surfaceVariant
    val head = MaterialTheme.colorScheme.onBackground

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let(onSeek); dragFrac = null },
                    onDragCancel = { dragFrac = null },
                )
            },
    ) {
        val bars = 120
        val gap = 2.dp.toPx()
        val bw = (size.width - gap * (bars - 1)) / bars
        val mid = size.height / 2
        for (i in 0 until bars) {
            val amp = if (peaks == null || peaks.isEmpty()) 0.3f else {
                val a = i * peaks.size / bars
                val b = max(a + 1, (i + 1) * peaks.size / bars)
                var m = 0f
                for (k in a until minOf(b, peaks.size)) if (peaks[k] > m) m = peaks[k]
                m
            }
            val h = max(3.dp.toPx(), amp.coerceIn(0f, 1f) * size.height * 0.95f)
            val x = i * (bw + gap)
            drawRoundRect(
                color = if ((i + 0.5f) / bars <= shown) played else rest,
                topLeft = Offset(x, mid - h / 2),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2),
            )
        }
        val hx = shown.coerceIn(0f, 1f) * size.width
        drawLine(head, Offset(hx, 0f), Offset(hx, size.height), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun Transport(playing: Boolean, onToggle: () -> Unit, onBack: () -> Unit, onFwd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.Replay5, "Back 5 seconds", Modifier.size(32.dp))
        }
        Spacer(Modifier.width(20.dp))
        FilledIconButton(onClick = onToggle, modifier = Modifier.size(76.dp)) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "Pause" else "Play",
                Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        IconButton(onClick = onFwd, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.Forward5, "Forward 5 seconds", Modifier.size(32.dp))
        }
    }
}

@Composable
private fun KeyCard(song: Song, vm: PlayerViewModel) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (song.semitones != 0 || song.cents != 0) {
                    TextButton(onClick = vm::resetKey) { Text("Original") }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { vm.setSemitones(song.semitones - 1) },
                    enabled = song.semitones > -12,
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Default.Remove, "Key down") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (song.semitones > 0) "+${song.semitones}" else "${song.semitones}",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (song.semitones == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        when {
                            song.semitones == 0 -> "original key"
                            song.semitones > 0 -> "semitone${if (song.semitones == 1) "" else "s"} up"
                            else -> "semitone${if (song.semitones == -1) "" else "s"} down"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(
                    onClick = { vm.setSemitones(song.semitones + 1) },
                    enabled = song.semitones < 12,
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Default.Add, "Key up") }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fine tune", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    if (song.cents == 0) "0 cents" else "%+d cents".format(song.cents),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Slider(
                value = song.cents.toFloat(),
                onValueChange = { vm.setCents(((it / 5f).roundToInt() * 5)) },
                valueRange = -50f..50f,
                steps = 19,
            )
            Text(
                "To match a harmonium or a guitar that isn't at standard pitch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Natural voice (beta)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Keeps the singer's tone natural when you change key a lot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = song.naturalVoice, onCheckedChange = vm::setNaturalVoice)
            }
        }
    }
}

@Composable
private fun SpeedCard(song: Song, vm: PlayerViewModel) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${(song.tempo * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                if (song.tempo != 1f) TextButton(onClick = { vm.setTempo(1f) }) { Text("Normal") }
            }
            Slider(
                value = song.tempo,
                onValueChange = { vm.setTempo((it * 20f).roundToInt() / 20f) },
                valueRange = 0.5f..1.5f,
                steps = 19,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.75f, 0.9f, 1f).forEach { t ->
                    val selected = song.tempo == t
                    TextButton(
                        onClick = { vm.setTempo(t) },
                        colors = if (selected) androidx.compose.material3.ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ) else androidx.compose.material3.ButtonDefaults.textButtonColors(),
                    ) { Text("${(t * 100).roundToInt()}%") }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(song: Song, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                song.artist?.let(::add)
                add(fmt(song.durationMs))
                if (song.semitones != 0) add("key ${if (song.semitones > 0) "+" else ""}${song.semitones}")
                if (song.tempo != 1f) add("${(song.tempo * 100).roundToInt()}%")
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmt(ms: Long): String {
    val t = ms / 1000
    return "%d:%02d".format(t / 60, t % 60)
}
