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
import com.riyaz.app.analysis.AnalysisStore
import com.riyaz.app.analysis.SongAnalysis
import com.riyaz.app.analysis.SongAnalyzer
import com.riyaz.app.audio.NativeEngine
import com.riyaz.app.audio.Song
import com.riyaz.app.audio.SongLibrary
import com.riyaz.app.youtube.YouTube
import com.riyaz.app.youtube.YtResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something slow is happening (download / decode). [progress] null = indeterminate. */
data class Busy(val label: String, val title: String?, val progress: Float?)

data class SearchState(
    val query: String = "",
    val source: YouTube.Source = YouTube.Source.YOUTUBE,
    val link: String? = null,            // a YouTube link found in the query box
    val results: List<YtResult> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val searchedFor: String? = null,     // the query the results belong to
)

data class PlayerState(
    val song: Song? = null,
    val peaks: FloatArray? = null,
    val busy: Busy? = null,
    val error: String? = null,
    val playing: Boolean = false,
    val recent: List<Song> = emptyList(),
    val showPlayer: Boolean = false,
    val search: SearchState = SearchState(),
    val analysis: SongAnalysis? = null,
    val analyzing: Float? = null,          // progress while chords are being worked out
    val analysisError: String? = null,
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

    private var searchJob: Job? = null
    private var openJob: Job? = null
    private var analysisJob: Job? = null
    private val analyzer = SongAnalyzer(app)

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

    // ------------------------------------------------------------------ navigation

    fun showPlayer() = _state.update { if (it.song != null) it.copy(showPlayer = true) else it }
    fun showHome() = _state.update { it.copy(showPlayer = false) }

    // ------------------------------------------------------------------ search

    fun setQuery(q: String) {
        _state.update { it.copy(search = it.search.copy(query = q, link = YouTube.findLink(q), error = null)) }
    }

    fun setSource(source: YouTube.Source) {
        _state.update { it.copy(search = it.search.copy(source = source)) }
        val s = _state.value.search
        if (s.query.isNotBlank() && s.link == null) search()
    }

    fun search() {
        val s = _state.value.search
        val q = s.query.trim()
        if (q.isEmpty()) return
        s.link?.let { openYouTube(it); return }
        searchJob?.cancel()
        _state.update { it.copy(search = it.search.copy(searching = true, error = null)) }
        searchJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { YouTube.search(q, s.source) }
                _state.update { it.copy(search = it.search.copy(results = results, searchedFor = q, searching = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "search failed", e)
                _state.update { it.copy(search = it.search.copy(searching = false, error = e.message)) }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = SearchState(source = it.search.source)) }
    }

    /** Text shared from another app: a YouTube link opens straight away, anything else is searched. */
    fun handleSharedText(text: String) {
        val link = YouTube.findLink(text)
        if (link != null) {
            openYouTube(link)
        } else {
            setQuery(text.trim().take(200))
            _state.update { it.copy(showPlayer = false) }
            search()
        }
    }

    // ------------------------------------------------------------------ opening songs

    fun open(uri: Uri) = startOpen(null) {
        val t0 = SystemClock.elapsedRealtime()
        busy("Preparing song…", null, 0f)
        library.import(uri) { p -> progress(p) }
            .also { Log.i(TAG, "import ${it.title}: ${SystemClock.elapsedRealtime() - t0} ms") }
    }

    fun openYouTube(result: YtResult) = openYouTube(result.url, result.title)

    fun openYouTube(url: String, title: String? = null) = startOpen(title) {
        val videoId = YouTube.videoId(url) ?: throw IllegalArgumentException("That doesn't look like a YouTube video link")
        val id = library.youtubeId(videoId)
        library.cached(id)?.let { return@startOpen it }   // seen before: instant

        val t0 = SystemClock.elapsedRealtime()
        busy("Downloading…", title, null)  // indeterminate until bytes arrive
        val dl = try {
            YouTube.downloadAudio(url, library.folderFor(id)) { p -> progress(p) }
        } catch (e: Throwable) {
            library.discard(id)
            throw e
        }
        val t1 = SystemClock.elapsedRealtime()
        busy("Preparing song…", dl.title, 0f)
        library.importDownloaded(id, dl.file, dl.title, dl.channel, dl.url, dl.thumbnailUrl) { p -> progress(p) }
            .also {
                Log.i(TAG, "youtube ${dl.videoId} ${dl.file.name}: download ${t1 - t0} ms, decode ${SystemClock.elapsedRealtime() - t1} ms")
            }
    }

    fun cancelOpen() {
        openJob?.cancel()
    }

    fun openRecent(song: Song) {
        if (song.id == _state.value.song?.id) {
            showPlayer()
            return
        }
        startOpen(song.title) { library.touch(song) }
    }

    /** Runs [block] (which produces a decoded song) and then loads it into the player. */
    private fun startOpen(title: String?, block: suspend () -> Song) {
        if (_state.value.busy != null) return
        pause()
        _state.update { it.copy(busy = Busy("Opening…", title, null), error = null) }
        openJob = viewModelScope.launch {
            try {
                val song = withContext(Dispatchers.IO) { block() }
                load(song)
            } catch (e: CancellationException) {
                // user cancelled: nothing to report
            } catch (e: Throwable) {
                Log.w(TAG, "open failed", e)
                _state.update { it.copy(error = e.message ?: "Couldn't open that") }
            } finally {
                _state.update { it.copy(busy = null, recent = library.recent()) }
            }
        }
    }

    private fun busy(label: String, title: String?, progress: Float?) =
        _state.update { it.copy(busy = Busy(label, title ?: it.busy?.title, progress)) }

    /** Progress callback from blocking IO; also the place a cancel takes effect. */
    private fun progress(p: Float) {
        if (openJob?.isCancelled == true) throw CancellationException("cancelled")
        _state.update { s -> s.busy?.let { s.copy(busy = it.copy(progress = p)) } ?: s }
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
        val peaks = withContext(Dispatchers.IO) { library.overview(song) }
        _positionMs.value = 0
        _state.update {
            it.copy(song = song, peaks = peaks, playing = false, error = null, showPlayer = true,
                analysis = null, analyzing = null, analysisError = null)
        }
        startAnalysis(song)
    }

    // ------------------------------------------------------------------ chords

    /** Loads the saved analysis, or works it out in the background (playback is unaffected). */
    private fun startAnalysis(song: Song) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val dir = library.dir(song)
            val saved = withContext(Dispatchers.IO) { AnalysisStore.load(dir) }
            if (saved != null) {
                _state.update { if (it.song?.id == song.id) it.copy(analysis = saved) else it }
                return@launch
            }
            _state.update { it.copy(analyzing = 0f) }
            // the native feature step reports its own progress; mirror it into the UI
            val poll = launch {
                while (isActive) {
                    val p = NativeEngine.nativeAnalysisProgress() * 0.7f
                    _state.update { s -> if (s.song?.id == song.id && s.analyzing != null && p > (s.analyzing ?: 0f)) s.copy(analyzing = p) else s }
                    delay(100)
                }
            }
            try {
                val result = withContext(Dispatchers.Default) {
                    analyzer.analyze(library.pcmFile(song).absolutePath, song.sampleRate) { p ->
                        _state.update { s -> if (s.song?.id == song.id) s.copy(analyzing = p) else s }
                    }
                }
                withContext(Dispatchers.IO) { AnalysisStore.save(dir, result) }
                _state.update { if (it.song?.id == song.id) it.copy(analysis = result, analyzing = null) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "analysis failed", e)
                _state.update { if (it.song?.id == song.id) it.copy(analyzing = null, analysisError = "Couldn't work out the chords for this song.") else it }
            } finally {
                poll.cancel()
            }
        }
    }

    fun setCapo(fret: Int) = updateSong { it.copy(capo = fret.coerceIn(0, 9)) }
    fun setSimplifyChords(on: Boolean) = updateSong { it.copy(simplifyChords = on) }

    /** The beat tracker sometimes counts double or half time: tap the BPM to cycle x1 -> x0.5 -> x2. */
    fun cycleBpmFactor() = updateSong {
        it.copy(bpmFactor = when (it.bpmFactor) { 1f -> 0.5f; 0.5f -> 2f; else -> 1f })
    }

    // ------------------------------------------------------------------ playback

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
        // Keep the Recent list in step, so it never shows stale key/speed.
        _state.update { s -> s.copy(song = updated, recent = s.recent.map { if (it.id == updated.id) updated else it }) }
        viewModelScope.launch(Dispatchers.IO) { library.save(updated) }
    }

    override fun onCleared() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        NativeEngine.nativeUnload()
    }
}
