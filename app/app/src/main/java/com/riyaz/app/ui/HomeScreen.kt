package com.riyaz.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.riyaz.app.Busy
import com.riyaz.app.PlayerViewModel
import com.riyaz.app.audio.Song
import com.riyaz.app.youtube.YouTube
import com.riyaz.app.youtube.YtResult
import com.riyaz.app.Tool
import com.riyaz.app.voice.Notes
import com.riyaz.app.voice.VoiceProfile
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: PlayerViewModel) {
    val s by vm.state.collectAsState()
    val search = s.search
    val focus = LocalFocusManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Riyaz", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = s.busy == null) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open a file from the phone")
                    }
                },
            )
        },
        bottomBar = {
            s.song?.let { song -> NowPlayingBar(song, s.playing, onOpen = vm::showPlayer, onToggle = vm::togglePlay) }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = search.query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search a song or paste a YouTube link") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (search.query.isNotEmpty()) {
                                IconButton(onClick = vm::clearSearch) { Icon(Icons.Default.Close, "Clear") }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        // No autocorrect: it "fixes" song names ("Uska hi bana" -> "banana") and breaks pasted links.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YouTube.Source.entries.forEach { src ->
                            FilterChip(
                                selected = search.source == src,
                                onClick = { focus.clearFocus(); vm.setSource(src) },
                                label = { Text(src.label) },
                            )
                        }
                    }
                    search.link?.let { link ->
                        Card(
                            onClick = { focus.clearFocus(); vm.openYouTube(link) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Open this YouTube link", style = MaterialTheme.typography.titleSmall)
                                    Text(link, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    s.busy?.let { BusyCard(it, onCancel = vm::cancelOpen) }
                    s.error?.let { ErrorCard(it, vm::dismissError) }
                    search.error?.let { ErrorCard(it) { vm.setQuery(search.query) } }
                    if (search.searching) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            if (search.searchedFor == null && !search.searching) {
                item { ToolsRow(s.voice, s.learned, onTuner = { vm.openTool(Tool.TUNER) }, onVoice = { vm.openTool(Tool.VOICE_TEST) }, onSongbook = { vm.openTool(Tool.SONGBOOK) }) }
            }

            if (search.searchedFor != null && !search.searching) {
                item { SectionTitle("Results for “${search.searchedFor}”") }
                if (search.results.isEmpty()) {
                    item { Hint("Nothing found. Try other words, or switch between YouTube and YouTube Music.") }
                }
                items(search.results, key = { "r-" + it.videoId }) { r ->
                    ResultRow(r, enabled = s.busy == null) { focus.clearFocus(); vm.openYouTube(r) }
                }
            }

            if (s.recent.isNotEmpty()) {
                item { SectionTitle("Recent") }
                items(s.recent, key = { "s-" + it.id }) { song ->
                    RecentRow(song, enabled = s.busy == null) { vm.openRecent(song) }
                }
            } else if (search.searchedFor == null) {
                item {
                    Hint("Search for any song, paste a YouTube link, share a video here from the YouTube app, or open a file from your phone (folder icon).")
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ToolsRow(voice: VoiceProfile?, learned: com.riyaz.app.voice.LearnedVoice, onTuner: () -> Unit, onVoice: () -> Unit, onSongbook: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(onClick = onTuner, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(14.dp)) {
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text("Tuner", style = MaterialTheme.typography.titleSmall)
                Text("Any note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(onClick = onVoice, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(14.dp)) {
                Icon(Icons.Default.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text("My voice", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    com.riyaz.app.voice.effectiveRange(voice, learned)?.let { (lo, hi) -> "${Notes.name(lo)} – ${Notes.name(hi)}" + if (learned.sessions > 0) " · ${learned.sessions} songs" else "" } ?: "Find your range",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Card(onClick = onSongbook, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(14.dp)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text("Songbook", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("Chord sheets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun BusyCard(busy: Busy, onCancel: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(busy.label, style = MaterialTheme.typography.titleSmall)
                    busy.title?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(8.dp))
            val p = busy.progress
            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Thumb(url: String?) {
    Box(
        Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultRow(r: YtResult, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(r.thumbnailUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(r.channel, r.durationSec.takeIf { it > 0 }?.let { fmt(it * 1000) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentRow(song: Song, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(song.thumbnailUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                song.artist?.let(::add)
                add(fmt(song.durationMs))
                if (song.semitones != 0) add("key ${if (song.semitones > 0) "+" else ""}${song.semitones}")
                if (song.tempo != 1f) add("${(song.tempo * 100).toInt()}%")
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NowPlayingBar(song: Song, playing: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(song.thumbnailUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val key = if (song.semitones == 0) "original key" else "key ${if (song.semitones > 0) "+" else ""}${song.semitones}"
                Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggle) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play")
            }
        }
    }
}
