package com.riyaz.app

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riyaz.app.audio.NativeEngine
import com.riyaz.app.audio.Song
import com.riyaz.app.audio.SongLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerState(
    val song: Song? = null,
    val peaks: FloatArray? = null,
    val decoding: Boolean = false,
    val decodeProgress: Float = 0f,
    val error: String? = null,
    val playing: Boolean = false,
    val recent: List<Song> = emptyList(),
)

private const val TAG = "Riyaz"

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val library = SongLibrary(app)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(PlayerState(recent = library.recent()))
    val state: StateFlow<PlayerState> = _state

    // Kept separate from [state] so the 20-per-second playhead updates only redraw the playhead.
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            // A call, another music app, or a voice note: stop so we don't play over it.
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) pause()
        }
        .build()

    init {
        // Poll the engine for position / end-of-song while a song is loaded.
        viewModelScope.launch {
            while (isActive) {
                val song = _state.value.song
                if (song != null) {
                    val pos = NativeEngine.nativePosition() * 1000L / song.sampleRate
                    val playing = NativeEngine.nativeIsPlaying()
                    // Song reached its end: give audio focus back to other apps.
                    if (_state.value.playing && !playing) audioManager.abandonAudioFocusRequest(focusRequest)
                    _positionMs.value = pos
                    if (playing != _state.value.playing) _state.update { it.copy(playing = playing) }
                }
                delay(50)
            }
        }
    }

    fun open(uri: Uri) {
        if (_state.value.decoding) return
        pause()
        _state.update { it.copy(decoding = true, decodeProgress = 0f, error = null) }
        viewModelScope.launch {
            try {
                val song = withContext(Dispatchers.IO) {
                    val t0 = SystemClock.elapsedRealtime()
                    library.import(uri) { p -> _state.update { it.copy(decodeProgress = p) } }
                        .also { Log.i(TAG, "import ${it.title}: ${SystemClock.elapsedRealtime() - t0} ms") }
                }
                load(song)
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Couldn't open that file") }
            } finally {
                _state.update { it.copy(decoding = false, recent = library.recent()) }
            }
        }
    }

    fun openRecent(song: Song) {
        pause()
        viewModelScope.launch {
            load(withContext(Dispatchers.IO) { library.touch(song) })
            _state.update { it.copy(recent = library.recent()) }
        }
    }

    private suspend fun load(song: Song) {
        val ok = withContext(Dispatchers.IO) {
            NativeEngine.nativeLoad(library.pcmFile(song).absolutePath, song.sampleRate)
        }
        if (!ok) {
            _state.update { it.copy(error = "Couldn't start audio for this song") }
            return
        }
        applyToEngine(song)
        val t1 = SystemClock.elapsedRealtime()
        val peaks = withContext(Dispatchers.IO) { library.overview(song) }
        Log.i(TAG, "overview: ${SystemClock.elapsedRealtime() - t1} ms")
        _positionMs.value = 0
        _state.update { it.copy(song = song, peaks = peaks, playing = false, error = null) }
    }

    private fun applyToEngine(song: Song) {
        NativeEngine.nativeSetSemitones(song.semitones + song.cents / 100f)
        NativeEngine.nativeSetTempo(song.tempo)
        NativeEngine.nativeSetNaturalVoice(song.naturalVoice)
    }

    fun togglePlay() {
        if (_state.value.playing) pause() else play()
    }

    fun play() {
        if (_state.value.song == null) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        NativeEngine.nativePlay()
        _state.update { it.copy(playing = true) }
    }

    fun pause() {
        if (_state.value.song == null) return
        NativeEngine.nativePause()
        audioManager.abandonAudioFocusRequest(focusRequest)
        _state.update { it.copy(playing = false) }
    }

    fun seekTo(ms: Long) {
        val song = _state.value.song ?: return
        val clamped = ms.coerceIn(0, song.durationMs)
        NativeEngine.nativeSeek(clamped * song.sampleRate / 1000L)
        _positionMs.value = clamped
    }

    fun skip(deltaMs: Long) = seekTo(_positionMs.value + deltaMs)

    fun setSemitones(st: Int) = updateSong { it.copy(semitones = st.coerceIn(-12, 12)) }
    fun setCents(c: Int) = updateSong { it.copy(cents = c.coerceIn(-50, 50)) }
    fun setTempo(t: Float) = updateSong { it.copy(tempo = t.coerceIn(0.5f, 1.5f)) }
    fun setNaturalVoice(on: Boolean) = updateSong { it.copy(naturalVoice = on) }
    fun resetKey() = updateSong { it.copy(semitones = 0, cents = 0) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Changes a per-song setting: applies it live and remembers it for next time. */
    private fun updateSong(change: (Song) -> Song) {
        val song = _state.value.song ?: return
        val updated = change(song)
        applyToEngine(updated)
        _state.update { it.copy(song = updated) }
        viewModelScope.launch(Dispatchers.IO) { library.save(updated) }
    }

    override fun onCleared() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        NativeEngine.nativeUnload()
    }
}
