package com.riyaz.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.riyaz.app.voice.Notes
import com.riyaz.app.voice.PitchFrame
import com.riyaz.app.voice.PitchTracker
import com.riyaz.app.voice.RangeAnalysis
import com.riyaz.app.voice.VoiceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

/** Asks for the microphone once; shows [content] when allowed. */
@Composable
private fun WithMicrophone(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }
    if (granted) content() else {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Riyaz needs the microphone to hear your instrument or voice. Nothing is recorded or sent anywhere.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
        }
    }
}

/** Runs the pitch tracker while on screen, calling [onFrames] about 30 times a second. */
@Composable
private fun ListenWhileVisible(onFrames: (List<PitchFrame>) -> Unit) {
    val tracker = remember { PitchTracker() }
    val callback by rememberUpdatedState(onFrames)   // always the latest (e.g. the current test step)
    var micOk by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        micOk = tracker.start()
        onDispose { tracker.stop() }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            val f = tracker.poll()
            if (f.isNotEmpty()) callback(f)
            delay(33)
        }
    }
    if (!micOk) Text("Couldn't open the microphone. Is another app using it?", color = MaterialTheme.colorScheme.error)
}

// ------------------------------------------------------------------ tuner

private val GUITAR = listOf(40 to "E", 45 to "A", 50 to "D", 55 to "G", 59 to "B", 64 to "e")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var guitar by remember { mutableStateOf(true) }
    var a4 by remember { mutableFloatStateOf(440f) }
    val recent = remember { ArrayDeque<Float>() }
    var shownHz by remember { mutableFloatStateOf(0f) }
    var lastVoiced by remember { mutableStateOf(0L) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Tuner") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WithMicrophone {
                ListenWhileVisible { frames ->
                    for (f in frames) if (f.voiced) {
                        recent.addLast(f.hz); if (recent.size > 10) recent.removeFirst()
                        lastVoiced = f.tMs
                    }
                    // median of the last ~10 readings: steady, but follows a new string within ~0.2 s
                    if (recent.isNotEmpty()) shownHz = recent.sorted()[recent.size / 2]
                    if ((frames.last().tMs - lastVoiced) > 700) { shownHz = 0f; recent.clear() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = guitar, onClick = { guitar = true }, label = { Text("Guitar (EADGBE)") })
                    FilterChip(selected = !guitar, onClick = { guitar = false }, label = { Text("Chromatic") })
                }
                val midi = if (shownHz > 0) Notes.midi(shownHz, a4) else null
                val target: Int? = midi?.let { m -> if (guitar) GUITAR.minByOrNull { abs(it.first - m) }!!.first else m.roundToInt() }
                val cents = if (midi != null && target != null) ((midi - target) * 100f).coerceIn(-50f, 50f) else null
                if (guitar) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GUITAR.forEach { (m, label) ->
                            val active = m == target
                            Text(label, fontSize = 22.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(target?.let { Notes.name(it) } ?: "–", fontSize = 72.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        cents == null -> if (guitar) "Play a string" else "Play or sing a note"
                        abs(cents) <= 5 -> "In tune"
                        cents < 0 -> "Too low: tune up  ${"%+.0f".format(cents)} cents"
                        else -> "Too high: tune down  ${"%+.0f".format(cents)} cents"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        cents == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        abs(cents) <= 5 -> Color(0xFF7BD88F)
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                CentsMeter(cents)
                Text(if (shownHz > 0) "%.1f Hz".format(shownHz) else " ", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reference A4", modifier = Modifier.weight(1f))
                    IconButton(onClick = { a4 = (a4 - 1f).coerceAtLeast(430f) }) { Icon(Icons.Default.Remove, "Lower reference") }
                    Text("${a4.roundToInt()} Hz", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { a4 = (a4 + 1f).coerceAtMost(450f) }) { Icon(Icons.Default.Add, "Raise reference") }
                }
            }
        }
    }
}

@Composable
private fun CentsMeter(cents: Float?) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val good = Color(0xFF7BD88F)
    val needle = if (cents != null && abs(cents) <= 5) good else MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val mid = size.width / 2
        val y = size.height * 0.75f
        for (c in -50..50 step 10) {
            val x = mid + c / 50f * (size.width * 0.45f)
            drawLine(ink.copy(alpha = if (c == 0) 0.9f else 0.4f), Offset(x, y - if (c == 0) 34f else 18f), Offset(x, y), strokeWidth = if (c == 0) 4f else 2f)
        }
        drawRect(good.copy(alpha = 0.18f), topLeft = Offset(mid - 0.1f * size.width * 0.45f, y - 40f),
            size = androidx.compose.ui.geometry.Size(0.2f * size.width * 0.45f, 40f))
        if (cents != null) {
            val x = mid + cents / 50f * (size.width * 0.45f)
            drawLine(needle, Offset(x, 0f), Offset(x, y), strokeWidth = 10f, cap = StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ voice range test

private enum class Step(val title: String, val instruction: String, val seconds: Int) {
    HOLD("1 of 3: your easy note", "Sing \"aah\" on any note that feels easy, and hold it.", 5),
    DOWN("2 of 3: go low", "Slide down slowly to the lowest note you can sing comfortably. Hold it for a second at the bottom.", 9),
    UP("3 of 3: go high", "Now slide up slowly to the highest note you can sing comfortably, in your normal voice. Hold it at the top.", 9),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceTestScreen(current: VoiceProfile?, onSave: (VoiceProfile) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var stepIndex by remember { mutableIntStateOf(-1) }        // -1 intro, 0..2 steps, 3 result
    var remainingMs by remember { mutableStateOf(0L) }
    val trace = remember { mutableStateListOf<Float?>() }       // last ~5 s for the live graph
    val recorded = remember { List(3) { ArrayList<Float>() } }
    var result by remember { mutableStateOf<VoiceProfile?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Your vocal range") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                stepIndex < 0 -> {
                    Text("About one minute. Find a quiet spot and sing in your normal singing voice (not falsetto).",
                        style = MaterialTheme.typography.bodyLarge)
                    current?.let { ProfileCard(it, "Your saved range") }
                    Button(onClick = {
                        recorded.forEach { it.clear() }; trace.clear(); problem = null; result = null
                        stepIndex = 0; remainingMs = Step.HOLD.seconds * 1000L
                    }) { Text(if (current == null) "Start" else "Test again") }
                }
                stepIndex in 0..2 -> WithMicrophone {
                    val step = Step.entries[stepIndex]
                    ListenWhileVisible { frames ->
                        for (f in frames) {
                            val m = f.midi()
                            trace.add(m); if (trace.size > 320) trace.removeAt(0)
                            if (m != null) recorded[stepIndex].add(m)
                        }
                    }
                    LaunchedEffect(stepIndex) {
                        val end = System.currentTimeMillis() + step.seconds * 1000L
                        while (System.currentTimeMillis() < end) {
                            remainingMs = end - System.currentTimeMillis(); delay(100)
                        }
                        if (stepIndex < 2) stepIndex++ else {
                            val (p, why) = RangeAnalysis.analyse(recorded[0], recorded[1], recorded[2], System.currentTimeMillis())
                            result = p; problem = why; stepIndex = 3
                        }
                    }
                    Text(step.title, style = MaterialTheme.typography.titleLarge)
                    Text(step.instruction, style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(progress = { 1f - remainingMs / (step.seconds * 1000f) }, modifier = Modifier.fillMaxWidth())
                    val now = trace.lastOrNull { it != null }
                    Text(now?.let { Notes.name(it) } ?: "…", fontSize = 56.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    PitchTrace(trace, Modifier.weight(1f))
                }
                else -> {
                    val p = result
                    if (p != null) {
                        ProfileCard(p, "Your vocal range")
                        Text("Saved. Riyaz will use this to suggest a key for each song, and keep learning from your karaoke sessions.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LaunchedEffect(p) { onSave(p) }
                    } else {
                        Text(problem ?: "Something went wrong.", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { stepIndex = -1 }) { Text("Test again") }
                        Button(onClick = onBack) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileCard(p: VoiceProfile, title: String) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${Notes.name(p.comfortableLow)} – ${Notes.name(p.comfortableHigh)}", fontSize = 34.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("Comfortable range, ${"%.1f".format(p.octaves)} octaves. You can reach ${Notes.name(p.extremeLow)} – ${Notes.name(p.extremeHigh)}.",
                style = MaterialTheme.typography.bodyMedium)
            Text("Easiest note: ${Notes.name(p.center)} · closest voice type: ${p.voiceType()}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RangeBar(p)
        }
    }
}

/** Keyboard-style bar from C2 to C6 with the comfortable and reachable ranges marked. */
@Composable
private fun RangeBar(p: VoiceProfile) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val reach = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val comfy = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val lo = 36f; val hi = 84f
        fun x(m: Float) = (m - lo) / (hi - lo) * size.width
        val y = size.height * 0.35f
        drawLine(ink.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), strokeWidth = 10f, cap = StrokeCap.Round)
        drawLine(reach, Offset(x(p.extremeLow), y), Offset(x(p.extremeHigh), y), strokeWidth = 14f, cap = StrokeCap.Round)
        drawLine(comfy, Offset(x(p.comfortableLow), y), Offset(x(p.comfortableHigh), y), strokeWidth = 14f, cap = StrokeCap.Round)
        for (c in listOf(36, 48, 60, 72, 84)) {
            val t = measurer.measure(Notes.name(c), TextStyle(color = ink, fontSize = 10.sp))
            drawText(t, topLeft = Offset((x(c.toFloat()) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), y + 14f))
        }
    }
}

/** The last few seconds of pitch on a note grid (like a piano roll). */
@Composable
private fun PitchTrace(trace: List<Float?>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth()) {
        val voiced = trace.filterNotNull()
        val center = voiced.lastOrNull() ?: 57f
        val lo = (minOf(voiced.minOrNull() ?: center, center - 6f) - 2f).roundToInt()
        val hi = (maxOf(voiced.maxOrNull() ?: center, center + 6f) + 2f).roundToInt()
        fun y(m: Float) = size.height - (m - lo) / (hi - lo) * size.height
        for (m in lo..hi) {
            if (Math.floorMod(m, 12) == 0 || (hi - lo <= 24 && Math.floorMod(m, 12) in setOf(4, 7, 9))) {
                drawLine(ink.copy(alpha = 0.25f), Offset(36f, y(m.toFloat())), Offset(size.width, y(m.toFloat())), strokeWidth = 1f)
                drawText(measurer.measure(Notes.name(m), TextStyle(color = ink, fontSize = 9.sp)), topLeft = Offset(0f, y(m.toFloat()) - 14f))
            }
        }
        val n = 320
        val dx = (size.width - 40f) / n
        var prev: Offset? = null
        trace.forEachIndexed { i, m ->
            if (m == null) { prev = null; return@forEachIndexed }
            val pt = Offset(40f + (n - trace.size + i) * dx, y(m))
            prev?.let { drawLine(line, it, pt, strokeWidth = 5f, cap = StrokeCap.Round) }
            prev = pt
        }
    }
}
