package com.riyaz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.riyaz.app.songbook.ChordSheetParser
import com.riyaz.app.songbook.SongbookViewModel
import com.riyaz.app.ui.HomeScreen
import com.riyaz.app.ui.SongbookScreen
import com.riyaz.app.youtube.YouTube
import com.riyaz.app.ui.KaraokeScreen
import com.riyaz.app.ui.TunerScreen
import com.riyaz.app.ui.VoiceTestScreen
import com.riyaz.app.ui.PlayerScreen
import com.riyaz.app.ui.RiyazTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()
    private val sb: SongbookViewModel by viewModels()

    // Headphones unplugged / Bluetooth disconnected: pause instead of blasting the speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) vm.pause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Keep the screen awake while a song plays (you're holding a guitar, not the phone).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s ->
                    if (s.playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            RiyazTheme {
                val s by vm.state.collectAsState()
                when {
                    s.tool == Tool.TUNER -> TunerScreen(onBack = vm::closeTool)
                    s.tool == Tool.VOICE_TEST -> VoiceTestScreen(s.voice, onSave = vm::saveVoiceProfile, onBack = vm::closeTool)
                    s.tool == Tool.SONGBOOK -> SongbookScreen(sb, vm, onClose = vm::closeTool)
                    s.showKaraoke && s.song != null -> KaraokeScreen(vm)
                    s.showPlayer && s.song != null -> PlayerScreen(vm, onSongSheet = ::openSongSheet)
                    else -> HomeScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * "Open with" / "Share" from a file manager, WhatsApp or a recorder (a file), or "Share" from the
     * YouTube app (text with a link).
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                // a chord sheet file (.txt / .cho) goes to the songbook; anything else is a song
                if (contentResolver.getType(uri)?.startsWith("text/") == true) { sb.importFile(uri); vm.openTool(Tool.SONGBOOK) }
                else vm.open(uri)
            }
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> vm.open(uri)
                    !text.isNullOrBlank() && YouTube.findLink(text) == null && ChordSheetParser.looksLikeSheet(text) -> {
                        sb.importText(text); vm.openTool(Tool.SONGBOOK)
                    }
                    !text.isNullOrBlank() -> vm.handleSharedText(text)
                }
            }
        }
    }

    /** The song's chord sheet: the one already in the songbook, or a new one from its chords and lyrics. */
    private fun openSongSheet() {
        val song = vm.state.value.song ?: return
        sb.state.value.sheets.firstOrNull { it.songId == song.id }?.let { sb.open(it.id); vm.openTool(Tool.SONGBOOK); return }
        vm.makeSongSheet { sheet ->
            if (sheet != null) { sb.openForSong(song.id) { sheet }; vm.openTool(Tool.SONGBOOK) }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        super.onDestroy()
    }
}
