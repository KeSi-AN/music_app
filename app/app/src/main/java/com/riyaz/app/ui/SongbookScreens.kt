package com.riyaz.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riyaz.app.PlayerViewModel
import com.riyaz.app.analysis.GuitarChords
import com.riyaz.app.lyrics.Romanization
import com.riyaz.app.songbook.ChordSheetParser
import com.riyaz.app.songbook.Part
import com.riyaz.app.songbook.SbScreen
import com.riyaz.app.songbook.Setlist
import com.riyaz.app.songbook.Sheet
import com.riyaz.app.songbook.SheetLine
import com.riyaz.app.songbook.SongbookViewModel
import com.riyaz.app.songbook.TextChord
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

@Composable
fun SongbookScreen(sb: SongbookViewModel, player: PlayerViewModel, onClose: () -> Unit) {
    val s by sb.state.collectAsState()
    BackHandler { if (!sb.back()) onClose() }
    when (val screen = s.screen) {
        SbScreen.Library -> LibraryScreen(sb, onClose)
        is SbScreen.View -> s.sheet(screen.id)?.let { SheetScreen(sb, player, it, screen.setlistId?.let(s::setlist)) }
            ?: LaunchedEffect(screen) { sb.go(SbScreen.Library) }
        is SbScreen.Edit -> EditScreen(sb, screen.draft, screen.isNew)
        is SbScreen.SetlistView -> s.setlist(screen.id)?.let { SetlistScreen(sb, it) }
            ?: LaunchedEffect(screen) { sb.go(SbScreen.Library) }
    }
}

// ---------------------------------------------------------------------------------------- library

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(sb: SongbookViewModel, onClose: () -> Unit) {
    val s by sb.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(sb::importFile) }
    var newSetlist by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Songbook", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = s.tab) {
                Tab(s.tab == 0, onClick = { sb.setTab(0) }, text = { Text("Sheets (${s.sheets.size})") })
                Tab(s.tab == 1, onClick = { sb.setTab(1) }, text = { Text("Setlists (${s.setlists.size})") })
            }
            s.message?.let { m ->
                Card(Modifier.padding(16.dp, 8.dp).fillMaxWidth().clickable { sb.dismissMessage() }) {
                    Text(m, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (s.tab == 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val text = clipboard.getText()?.text
                        if (text.isNullOrBlank()) sb.importText("") else sb.importText(text)
                    }, Modifier.weight(1f)) { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paste") }
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("text/*", "application/octet-stream")) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("File")
                    }
                    OutlinedButton(onClick = sb::newSheet, Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Write")
                    }
                }
                if (s.sheets.size > 6) {
                    OutlinedTextField(s.query, sb::setQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        placeholder = { Text("Find a sheet") }, singleLine = true)
                }
                val shown = s.sheets.filter { s.query.isBlank() || it.title.contains(s.query, true) || it.artist.contains(s.query, true) }
                if (s.sheets.isEmpty()) {
                    Text(
                        "Your chord sheets live here.\n\n• Copy a chord sheet from any website or chat and tap Paste, or share it to Riyaz.\n" +
                            "• Write your own: chords on the line above the words, or ChordPro like Tu[Am]jhe dekha.\n" +
                            "• For a song you've opened in Riyaz, tap Chord sheet under its chords: its chords over its lyrics.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { _, it -> it.id }) { _, sheet ->
                        ListItem(
                            headlineContent = { Text(sheet.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(sheetSubtitle(sheet), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(if (sheet.songId != null) Icons.Default.MusicNote else Icons.AutoMirrored.Filled.QueueMusic, null) },
                            modifier = Modifier.clickable { sb.open(sheet.id) },
                        )
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    FilledTonalButton(onClick = { newSetlist = true }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New setlist") }
                }
                if (s.setlists.isEmpty()) Text("A setlist is the songs for a gig or a practice, in order. Open one to go from sheet to sheet.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(s.setlists, key = { _, it -> it.id }) { _, l ->
                        ListItem(
                            headlineContent = { Text(l.name) },
                            supportingContent = { Text("${l.sheetIds.size} song${if (l.sheetIds.size == 1) "" else "s"}") },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            modifier = Modifier.clickable { sb.go(SbScreen.SetlistView(l.id)) },
                        )
                    }
                }
            }
        }
    }
    if (newSetlist) NameDialog("New setlist", "", onDismiss = { newSetlist = false }) { name ->
        newSetlist = false
        sb.go(SbScreen.SetlistView(sb.newSetlist(name)))
    }
}

private fun sheetSubtitle(s: Sheet) = listOfNotNull(
    s.artist.ifBlank { null },
    if (s.transpose != 0) "key ${if (s.transpose > 0) "+" else ""}${s.transpose}" else null,
    if (s.capo > 0) "capo ${s.capo}" else null,
).joinToString(" · ").ifEmpty { "Chord sheet" }

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Sunday jam") }) },
        confirmButton = { TextButton(onClick = { onOk(name) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------------------------------ sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetScreen(sb: SongbookViewModel, player: PlayerViewModel, sheet: Sheet, setlist: Setlist?) {
    val sbState by sb.state.collectAsState()
    val ps by player.state.collectAsState()
    val parsed = remember(sheet.text) { ChordSheetParser.parse(sheet.text) }
    val lines = remember(parsed) { parsed.lines.map { if (it is SheetLine.Lyrics) it.copy(parts = romanizeParts(it.parts)) else it } }
    val shapesShift = sheet.transpose - sheet.capo
    val flats = remember(parsed, shapesShift) { parsed.guessKey()?.transposed(shapesShift)?.usesFlats ?: false }
    val scroll = rememberScrollState()
    var autoscroll by remember(sheet.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var addToSetlist by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var chordShown by remember { mutableStateOf<TextChord?>(null) }
    val lineY = remember(sheet.id) { mutableStateMapOf<Int, Int>() }
    val density = LocalDensity.current
    val linkedLoaded = sheet.songId != null && ps.song?.id == sheet.songId
    val following = linkedLoaded && ps.playing && parsed.timed
    val inLibrary = sheet.songId != null && ps.recent.any { it.id == sheet.songId }

    // a performer's screen shouldn't go dark mid-song
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    // autoscroll at the sheet's speed (1..10 → 8..80 dp per second)
    LaunchedEffect(autoscroll, sheet.speed, following) {
        if (!autoscroll || following) return@LaunchedEffect
        val pxPerSec = with(density) { (8 * sheet.speed).dp.toPx() }
        var last = androidx.compose.runtime.withFrameNanos { it }
        var carry = 0f
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                carry += pxPerSec * (now - last) / 1e9f
                last = now
            }
            if (carry >= 1f) { scroll.dispatchRawDelta(carry); carry = 0f }
            if (scroll.value >= scroll.maxValue) { autoscroll = false; break }
        }
    }
    // follow the song while it plays, line by line (sheets Riyaz made from a song with synced lyrics have line times)
    LaunchedEffect(following, parsed) {
        if (!following) return@LaunchedEffect
        val timedIdx = lines.indices.filter { (lines[it] as? SheetLine.Lyrics)?.time != null }
        var lastLine = -1
        player.positionMs   // a StateFlow (not Compose state), so collect it directly
            .collect { ms ->
                val sec = ms / 1000.0
                val cur = timedIdx.lastOrNull { (lines[it] as SheetLine.Lyrics).time!! <= sec + 0.3 } ?: return@collect
                if (cur == lastLine) return@collect
                lastLine = cur
                val target = (lineY[cur] ?: return@collect) - scroll.viewportSize / 3
                val clamped = target.coerceIn(0, scroll.maxValue)
                if (kotlin.math.abs(clamped - scroll.value) > 4) scroll.animateScrollTo(clamped)
            }
    }

    val idx = setlist?.sheetIds?.indexOf(sheet.id) ?: -1
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sheet.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(sheet.artist.ifBlank { null }, setlist?.let { "${it.name} · ${idx + 1}/${it.sheetIds.size}" }).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = { sb.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { sb.edit(sheet.id) }) { Icon(Icons.Default.Edit, "Edit") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Add to setlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                onClick = { menu = false; addToSetlist = true })
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                if (setlist != null) {
                    IconButton(onClick = { sb.open(setlist.sheetIds[idx - 1], setlist.id) }, enabled = idx > 0) { Icon(Icons.Default.SkipPrevious, "Previous song") }
                }
                if (following) {
                    Text("Following the song", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
                } else {
                    FilledTonalIconButton(onClick = { autoscroll = !autoscroll }) {
                        Icon(if (autoscroll) Icons.Default.Pause else Icons.Default.SwapVert, if (autoscroll) "Stop scrolling" else "Autoscroll")
                    }
                    IconButton(onClick = { sb.change(sheet.id) { it.copy(speed = (it.speed - 1).coerceAtLeast(1)) } }) { Icon(Icons.Default.Remove, "Slower") }
                    Text("${sheet.speed}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { sb.change(sheet.id) { it.copy(speed = (it.speed + 1).coerceAtMost(10)) } }) { Icon(Icons.Default.Add, "Faster") }
                    Spacer(Modifier.weight(1f))
                }
                if (inLibrary) {
                    FilledTonalIconButton(onClick = {
                        if (linkedLoaded) player.togglePlay() else if (player.openSongById(sheet.songId!!)) Unit
                    }) {
                        Icon(if (linkedLoaded && ps.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (linkedLoaded && ps.playing) "Pause the song" else "Play the song")
                    }
                }
                if (setlist != null) {
                    IconButton(onClick = { sb.open(setlist.sheetIds[idx + 1], setlist.id) }, enabled = idx in 0 until setlist.sheetIds.size - 1) {
                        Icon(Icons.Default.SkipNext, "Next song")
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SheetControls(sb, sheet, parsed.chords.isNotEmpty(), parsed.guessKey()?.transposed(sheet.transpose)?.shortName(),
                ps.busy?.title?.takeIf { sheet.songId != null })
            HorizontalDivider()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp)) {
                lines.forEachIndexed { i, line ->
                    Box(Modifier.onGloballyPositioned { lineY[i] = it.positionInParent().y.roundToInt() }) {
                        SheetLineView(line, shapesShift, flats, sheet.simplify, sheet.textScale) { chordShown = it }
                    }
                }
                Spacer(Modifier.height(240.dp))   // room to scroll the last lines up to eye level
            }
        }
    }

    chordShown?.let { c ->
        val shown = c.transposed(shapesShift)
        AlertDialog(
            onDismissRequest = { chordShown = null },
            title = { Text(shown.name(flats, sheet.simplify)) },
            text = {
                val f = GuitarChords.fingering((if (sheet.simplify) shown.toChord().simplified() else shown.toChord()))
                if (f != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ChordDiagram(f, 140.dp) }
                else Text("No diagram for this chord yet.")
            },
            confirmButton = { TextButton(onClick = { chordShown = null }) { Text("Close") } },
        )
    }
    if (addToSetlist) {
        var naming by remember { mutableStateOf(false) }
        if (naming) NameDialog("New setlist", "", onDismiss = { addToSetlist = false }) { name ->
            sb.addToSetlist(sb.newSetlist(name), sheet.id); addToSetlist = false
        } else AlertDialog(
            onDismissRequest = { addToSetlist = false },
            title = { Text("Add to setlist") },
            text = {
                Column {
                    sbState.setlists.forEach { l ->
                        ListItem(headlineContent = { Text(l.name) },
                            supportingContent = { if (sheet.id in l.sheetIds) Text("Already in it") },
                            modifier = Modifier.clickable { sb.addToSetlist(l.id, sheet.id); addToSetlist = false })
                    }
                    TextButton(onClick = { naming = true }) { Icon(Icons.Default.Add, null); Text("New setlist") }
                }
            },
            confirmButton = { TextButton(onClick = { addToSetlist = false }) { Text("Close") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${sheet.title}”?") },
        text = { Text("The sheet will be removed from your songbook and its setlists. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; sb.delete(sheet.id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Key, capo, simple chords and text size: one compact row that scrolls sideways on small phones. */
@Composable
private fun SheetControls(sb: SongbookViewModel, sheet: Sheet, hasChords: Boolean, keyName: String?, loading: String?) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (hasChords) {
            Stepper(keyName?.let { "Key $it" } ?: "Key", if (sheet.transpose > 0) "+${sheet.transpose}" else "${sheet.transpose}",
                { sb.change(sheet.id) { it.copy(transpose = (it.transpose - 1).coerceAtLeast(-11)) } },
                { sb.change(sheet.id) { it.copy(transpose = (it.transpose + 1).coerceAtMost(11)) } })
            Stepper("Capo", if (sheet.capo == 0) "–" else "${sheet.capo}",
                { sb.change(sheet.id) { it.copy(capo = (it.capo - 1).coerceAtLeast(0)) } },
                { sb.change(sheet.id) { it.copy(capo = (it.capo + 1).coerceAtMost(9)) } })
            FilterChip(sheet.simplify, onClick = { sb.change(sheet.id) { it.copy(simplify = !it.simplify) } }, label = { Text("Simple") })
        }
        IconButton(onClick = { sb.change(sheet.id) { it.copy(textScale = (it.textScale - 0.1f).coerceAtLeast(0.7f)) } }) { Icon(Icons.Default.TextDecrease, "Smaller text") }
        IconButton(onClick = { sb.change(sheet.id) { it.copy(textScale = (it.textScale + 0.1f).coerceAtMost(1.8f)) } }) { Icon(Icons.Default.TextIncrease, "Bigger text") }
        if (loading != null) Text("Loading song…", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Stepper(label: String, value: String, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onDown, Modifier.size(36.dp)) { Icon(Icons.Default.Remove, "$label down", Modifier.size(18.dp)) }
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(26.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onUp, Modifier.size(36.dp)) { Icon(Icons.Default.Add, "$label up", Modifier.size(18.dp)) }
    }
}

/**
 * Lyrics in other scripts are shown in English letters (as everywhere in Riyaz). The whole line is
 * romanised at once (Hindi needs the whole word), then the chords are put back at the same relative place.
 */
private fun romanizeParts(parts: List<Part>): List<Part> {
    val full = parts.joinToString("") { it.text }
    if (full.none { it.code > 0x7F && it.isLetter() }) return parts
    val rom = Romanization.instance.romanize(full)
    var acc = 0
    val cuts = parts.map { p -> acc.also { acc += p.text.length } }.map { b ->
        if (full.isEmpty()) 0 else {
            val want = (b.toDouble() * rom.length / full.length).roundToInt().coerceIn(0, rom.length)
            // stay on a word boundary if one is close
            (want - 2..want + 2).filter { it in 0..rom.length && (it == 0 || it == rom.length || rom[it - 1] == ' ') }
                .minByOrNull { kotlin.math.abs(it - want) } ?: want
        }
    }
    return parts.mapIndexed { i, p ->
        val from = cuts[i]
        val to = (cuts.getOrNull(i + 1) ?: rom.length).coerceAtLeast(from)
        p.copy(text = rom.substring(from, to))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetLineView(line: SheetLine, shift: Int, flats: Boolean, simplify: Boolean, scale: Float, onChord: (TextChord) -> Unit) {
    val lyricSize = (17 * scale).sp
    val chordSize = (15 * scale).sp
    val chordColor = MaterialTheme.colorScheme.primary
    when (line) {
        SheetLine.Blank -> Spacer(Modifier.height(14.dp))
        is SheetLine.Section -> Text(line.name, Modifier.padding(top = 6.dp, bottom = 2.dp), color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.Bold, fontSize = (14 * scale).sp)
        is SheetLine.Comment -> Text(line.text,
            Modifier.padding(vertical = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            fontStyle = FontStyle.Italic, fontSize = (14 * scale).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is SheetLine.Tab -> Text(line.text, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
            fontSize = (13 * scale).sp, softWrap = false)
        is SheetLine.Lyrics -> when {
            !line.hasChords -> Text(line.parts.joinToString("") { it.text }, fontSize = lyricSize)
            line.chordOnly -> FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                line.parts.forEach { p ->
                    if (p.chord != null) Text(p.chord.transposed(shift).name(flats, simplify), Modifier.clickable { onChord(p.chord) },
                        color = chordColor, fontWeight = FontWeight.Bold, fontSize = chordSize)
                    else Text(p.raw ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = chordSize)
                }
            }
            else -> FlowRow(Modifier.padding(vertical = 2.dp)) {
                // each word is its own piece so lines wrap between words; the chord sits on the piece it starts
                for (p in line.parts) {
                    val words = Regex("""\S+\s*|\s+""").findAll(p.text).map { it.value }.toList().ifEmpty { listOf("") }
                    words.forEachIndexed { wi, w ->
                        Column {
                            val c = if (wi == 0) p.chord else null
                            val label = c?.transposed(shift)?.name(flats, simplify) ?: if (wi == 0) p.raw.orEmpty() else ""
                            Text(label.ifEmpty { " " }, Modifier.padding(end = if (label.isNotEmpty()) 6.dp else 0.dp)
                                .then(if (c != null) Modifier.clickable { onChord(c) } else Modifier),
                                color = chordColor, fontWeight = FontWeight.Bold, fontSize = chordSize, maxLines = 1)
                            Text(w.replace(' ', ' '), fontSize = lyricSize, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- editor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScreen(sb: SongbookViewModel, draft: Sheet, isNew: Boolean) {
    var title by remember(draft.id) { mutableStateOf(draft.title) }
    var artist by remember(draft.id) { mutableStateOf(draft.artist) }
    var text by remember(draft.id) { mutableStateOf(draft.text) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (isNew) "New chord sheet" else "Edit sheet", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = { sb.back() }) { Icon(Icons.Default.Close, "Cancel") } },
            actions = {
                TextButton(onClick = { sb.save(draft.copy(title = title, artist = artist, text = text)) },
                    enabled = text.isNotBlank() || title.isNotBlank()) { Text("Save") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Song") }, singleLine = true)
            OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text("Artist") }, singleLine = true)
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 360.dp),
                label = { Text("Chords and lyrics") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("[Verse]\nAm           F\nTujhe dekha to ye jaana sanam\n\nor ChordPro:  Tu[Am]jhe dekha to ye [F]jaana sanam",
                    fontFamily = FontFamily.Monospace) },
            )
            Text("Chords on their own line sit over the words below them. Lines like [Chorus] or Verse 2: become headings, and guitar tab (e|--3--) stays as it is.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------- setlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetlistScreen(sb: SongbookViewModel, setlist: Setlist) {
    val s by sb.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sheets = setlist.sheetIds.mapNotNull(s::sheet)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(setlist.name, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = { sb.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete setlist") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { sheets.firstOrNull()?.let { sb.open(it.id, setlist.id) } }, enabled = sheets.isNotEmpty()) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Start")
                }
                OutlinedButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add songs") }
            }
            if (sheets.isEmpty()) Text("No songs yet. Add sheets from your songbook.", Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                itemsIndexed(sheets, key = { _, it -> it.id }) { i, sheet ->
                    ListItem(
                        leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.titleMedium) },
                        headlineContent = { Text(sheet.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(sheetSubtitle(sheet), maxLines = 1) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { sb.move(setlist.id, i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = { sb.move(setlist.id, i, 1) }, enabled = i < sheets.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                IconButton(onClick = { sb.removeFromSetlist(setlist.id, sheet.id) }) { Icon(Icons.Default.Close, "Remove from setlist") }
                            }
                        },
                        modifier = Modifier.clickable { sb.open(sheet.id, setlist.id) },
                    )
                }
            }
        }
    }
    if (adding) {
        val picked = remember { mutableStateMapOf<String, Boolean>() }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add songs") },
            text = {
                val choices = s.sheets.filter { it.id !in setlist.sheetIds }
                if (choices.isEmpty()) Text("Every sheet in your songbook is already in this setlist.")
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(choices, key = { _, it -> it.id }) { _, sh ->
                        Row(Modifier.fillMaxWidth().clickable { picked[sh.id] = !(picked[sh.id] ?: false) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(picked[sh.id] ?: false, { picked[sh.id] = it })
                            Text(sh.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    s.sheets.filter { picked[it.id] == true }.forEach { sb.addToSetlist(setlist.id, it.id) }
                    adding = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    if (renaming) NameDialog("Rename setlist", setlist.name, onDismiss = { renaming = false }) { renaming = false; sb.renameSetlist(setlist.id, it) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${setlist.name}”?") },
        text = { Text("The sheets stay in your songbook; only the setlist goes.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; sb.deleteSetlist(setlist.id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
