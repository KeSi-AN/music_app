package com.riyaz.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riyaz.app.FitState
import com.riyaz.app.PlayerViewModel
import com.riyaz.app.audio.Song
import com.riyaz.app.fit.KeyFit

/** "Fits your voice": what Riyaz understood about the song and the key + capo it suggests, with the reason. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FitCard(fit: FitState, song: Song, vm: PlayerViewModel, compact: Boolean = false) {
    val sug = fit.suggestion
    val applied = sug != null && song.semitones == sug.shift && song.capo == sug.capo && song.cents == 0
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        sug == null -> "Fit to your voice"
                        applied -> "Fitted to your voice"
                        else -> "Suggested: key ${KeyFit.signed(sug.shift)}" + if (sug.capo > 0) ", capo ${sug.capo}" else ""
                    },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (sug != null && !applied) FilledTonalButton(onClick = vm::applyFit) { Text("Use") }
                if (applied && (song.semitones != 0 || song.capo != 0)) TextButton(onClick = vm::useOriginalKey) { Text("Original") }
            }
            val chips = fit.understanding.chips()
            if (!compact && chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
                }
            }
            val text = sug?.reason ?: fit.needs
            if (text != null && (!compact || sug != null)) {
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = if (compact) 2 else Int.MAX_VALUE)
            }
        }
    }
}
