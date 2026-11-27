package com.riyaz.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.riyaz.app.PlayerState
import com.riyaz.app.PlayerViewModel
import com.riyaz.app.analysis.Chord
import com.riyaz.app.audio.Song

/**
 * Play along: strum with the song while the mic checks each chord. Live: the chord sounding now, green
 * when what you play fits it. Afterwards: how many chords you got right and which to practise.
 */
@Composable
fun PlayAlongPanel(vm: PlayerViewModel, song: Song, s: PlayerState) {
    val analysis = s.analysis ?: return
    val p = s.playAlong
    val flats = analysis.key.transposed(song.semitones).usesFlats
    fun name(c: Chord?) = c?.let { if (song.simplifyChords) it.simplified() else it }?.name(flats) ?: "–"
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.startPlayAlong() }
    val good = Color(0xFF4CAF50)
    val bad = MaterialTheme.colorScheme.error

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Play along", style = MaterialTheme.typography.titleMedium)
                    if (!p.on) Text("Strum with the song; Riyaz listens and checks your chords.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.on) FilledTonalButton(onClick = vm::stopPlayAlong) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Done") }
                else FilledTonalButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        vm.startPlayAlong(); if (!s.playing) vm.play()
                    } else ask.launch(Manifest.permission.RECORD_AUDIO)
                }) { Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(6.dp)); Text("Start") }
            }
            p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (p.on) {
                if (!p.headphones) Text("Use headphones: without them the mic hears the song as well as your guitar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                val color = when (p.ok) { true -> good; false -> bad; null -> MaterialTheme.colorScheme.outline }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(96.dp).border(4.dp, color, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text(name(p.expected), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(when (p.ok) {
                            true -> "That's it"
                            false -> "Sounds like ${name(p.sounds)}"
                            null -> if (s.playing) "Listening…" else "Play the song to start"
                        }, style = MaterialTheme.typography.titleMedium, color = color)
                        if (song.capo > 0 && p.expected != null) Text("Shape ${name(p.expected.transposed(-song.capo))} with capo ${song.capo}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            p.result?.let { r ->
                if (!r.enough) Text("Not enough playing heard to score. Strum a little louder, or closer to the phone.",
                    style = MaterialTheme.typography.bodyMedium)
                else {
                    Text("You played ${r.right} of ${r.played.size} chords right (${r.percent}%).", style = MaterialTheme.typography.titleSmall)
                    val trouble = r.trouble()
                    if (trouble.isNotEmpty()) Text("Practise: " + trouble.joinToString(", ") { (c, heard) ->
                        name(c) + (heard?.let { " (sounded like ${name(it)})" } ?: "")
                    }, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = vm::dismissPlayAlongResult) { Text("OK") }
            }
        }
    }
}
