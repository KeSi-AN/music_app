package com.riyaz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riyaz.app.KaraokeState
import com.riyaz.app.PlayerViewModel
import com.riyaz.app.audio.Song
import com.riyaz.app.lyrics.Romanization
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaraokeScreen(vm: PlayerViewModel) {
    val s by vm.state.collectAsState()
    val song = s.song ?: return
    val k = s.karaoke
    BackHandler(onBack = vm::closeKaraoke)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Karaoke", style = MaterialTheme.typography.titleMedium)
                        Text(song.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::closeKaraoke) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the player") }
                },
            )
        },
        bottomBar = { KaraokeTransport(vm, song, s.playing) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VocalsCard(vm, song, k)
            KeyAndChordRow(vm, song, s)
            LyricsPanel(vm, k, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VocalsCard(vm: PlayerViewModel, song: Song, k: KaraokeState) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val busy = k.preparing
            when {
                busy != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(busy.label, style = MaterialTheme.typography.titleSmall)
                            busy.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TextButton(onClick = vm::cancelPrepareKaraoke) { Text("Cancel") }
                    }
                    val p = busy.progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                k.backingReady -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vocals", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                song.vocalLevel < 0.02f -> "off"
                                song.vocalLevel > 0.98f -> "original"
                                else -> "${(song.vocalLevel * 100).roundToInt()}% guide"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Slider(value = song.vocalLevel, onValueChange = vm::setVocalLevel, valueRange = 0f..1f)
                    Row(Modifier.fillMaxWidth()) {
                        Text("Karaoke", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("Original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    Text("Make a karaoke track", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Removes the singer's voice on your phone. Takes a few minutes the first time for each song; after that it's instant.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    k.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = vm::prepareKaraoke) { Text("Remove vocals") }
                }
            }
        }
    }
}

@Composable
private fun KeyAndChordRow(vm: PlayerViewModel, song: Song, s: com.riyaz.app.PlayerState) {
    val positionMs by vm.positionMs.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { vm.setSemitones(song.semitones - 1) }, enabled = song.semitones > -12) { Icon(Icons.Default.Remove, "Key down") }
        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (song.semitones > 0) "+${song.semitones}" else "${song.semitones}", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                color = if (song.semitones == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
            Text("key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = { vm.setSemitones(song.semitones + 1) }, enabled = song.semitones < 12) { Icon(Icons.Default.Add, "Key up") }
        Spacer(Modifier.weight(1f))
        // current chord, for singers who play along
        val a = s.analysis
        if (a != null) {
            val i = a.chordAt(positionMs / 1000.0)
            val c = a.segments.getOrNull(i)?.chord
            if (c != null && !c.isNone) {
                val shown = c.transposed(song.semitones - song.capo).let { if (song.simplifyChords) it.simplified() else it }
                val flats = a.key.transposed(song.semitones - song.capo).usesFlats
                Column(horizontalAlignment = Alignment.End) {
                    Text(shown.name(flats), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                    Text(if (song.capo > 0) "chord · capo ${song.capo}" else "chord", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LyricsPanel(vm: PlayerViewModel, k: KaraokeState, modifier: Modifier) {
    val positionMs by vm.positionMs.collectAsState()
    val lyrics = k.lyrics
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var editing by remember { mutableStateOf(false) }
        if (lyrics == null || editing) {
            if (k.lyricsLoading) {
                Text("Looking for lyrics…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                k.lyricsMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(
                    value = k.lyricsQuery, onValueChange = { vm.setLyricsQuery(it) }, singleLine = true,
                    label = { Text("Song title - artist") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { editing = false; vm.fetchLyrics() }),
                )
                Row {
                    FilledTonalButton(onClick = { editing = false; vm.fetchLyrics() }) { Text("Find lyrics") }
                    if (lyrics != null) TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            }
            return@Column
        }

        // every language is shown in English letters (sound for sound, not translated)
        val shown = remember(lyrics) { lyrics.lines.map { Romanization.instance.romanize(it.text) } }
        val sec = positionMs / 1000.0
        val current = if (lyrics.synced || lyrics.lines.any { it.time != null }) lyrics.lineAt(sec) else -1
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    k.syncing -> "Tap the button as each line starts"
                    lyrics.synced -> "Synced lyrics"
                    else -> "Lyrics aren't timed for this recording"
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (!k.syncing) {
                TextButton(onClick = vm::startLyricsSync) { Text(if (lyrics.synced) "Re-sync" else "Sync") }
                TextButton(onClick = { editing = true }) { Text("Change") }
            } else {
                TextButton(onClick = vm::stopLyricsSync) { Text("Stop") }
            }
        }

        val list = rememberLazyListState()
        val focus = if (k.syncing) k.syncNext else current
        LaunchedEffect(focus) { if (focus >= 0) list.animateScrollToItem(maxOf(0, focus - 2)) }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(lyrics.lines) { i, line ->
                    val text = shown[i]
                    val isNow = if (k.syncing) i == k.syncNext else i == current
                    val past = if (k.syncing) i < k.syncNext else (current >= 0 && i < current)
                    Text(
                        text,
                        fontSize = if (isNow) 26.sp else 20.sp,
                        fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isNow -> MaterialTheme.colorScheme.primary
                            past -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !k.syncing && line.time != null) {
                            line.time?.let { vm.seekTo((it * 1000).toLong()) }
                        },
                    )
                }
            }
        }
        if (k.syncing) {
            Button(onClick = vm::tapLyricLine, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(
                    shown.getOrNull(k.syncNext)?.let { "Now: $it" } ?: "Done",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun KaraokeTransport(vm: PlayerViewModel, song: Song, playing: Boolean) {
    val positionMs by vm.positionMs.collectAsState()
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
            var drag by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = drag ?: if (song.durationMs > 0) positionMs.toFloat() / song.durationMs else 0f,
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { vm.seekTo((it * song.durationMs).toLong()) }; drag = null },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${fmt(positionMs)} / ${fmt(song.durationMs)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.skip(-5000) }) { Icon(Icons.Default.Replay5, "Back 5 seconds") }
                FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(56.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play")
                }
            }
        }
    }
}
