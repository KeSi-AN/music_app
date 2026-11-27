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
import com.riyaz.app.fit.KeyFit
import com.riyaz.app.fit.KeyHabits
import com.riyaz.app.fit.KeySuggestion
import com.riyaz.app.fit.SongUnderstanding
import com.riyaz.app.fit.Understanding
import com.riyaz.app.karaoke.VocalSeparator
import com.riyaz.app.voice.effectiveRange
import com.riyaz.app.voice.VoiceProfile
import com.riyaz.app.voice.LearnedVoice
import com.riyaz.app.voice.Melody
import com.riyaz.app.voice.PitchTracker
import com.riyaz.app.voice.SessionAnalysis
import com.riyaz.app.voice.SessionResult
import com.riyaz.app.voice.SongPreference
import com.riyaz.app.voice.SungFrame
import kotlin.math.roundToInt
import com.riyaz.app.voice.VoiceStore
import com.riyaz.app.lyrics.LyricLine
import com.riyaz.app.lyrics.Lyrics
import com.riyaz.app.lyrics.LyricsService
import com.riyaz.app.lyrics.LyricsStore
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val showKaraoke: Boolean = false,
    val tool: Tool? = null,
    val voice: VoiceProfile? = null,       // from the range test
    val learned: LearnedVoice = LearnedVoice(),
    val habits: KeyHabits = KeyHabits(),
    val karaoke: KaraokeState = KaraokeState(),
    val fit: FitState? = null,
)

/** Phase 6: what Riyaz understood about the song and the key it suggests for you ([needs] says what's missing). */
data class FitState(val understanding: SongUnderstanding, val suggestion: KeySuggestion?, val needs: String?)

enum class Tool { TUNER, VOICE_TEST }

data class KaraokeState(
    val backingReady: Boolean = false,
    val preparing: Busy? = null,           // making the instrumental
    val error: String? = null,
    val lyrics: Lyrics? = null,
    val lyricsLoading: Boolean = false,
    val lyricsMessage: String? = null,     // "no lyrics found" etc.
    val lyricsQuery: String = "",
    val syncing: Boolean = false,          // tap-along mode for unsynced lyrics
    val syncNext: Int = 0,                 // next line to stamp
    val melody: Melody? = null,            // the song's sung melody (needs the karaoke track)
    val melodyProgress: Float? = null,
    val singing: Boolean = false,          // mic on, following your voice
    val headphones: Boolean = false,
    val learn: Boolean = true,             // learn from this session (off when a friend sings)
    val lastSession: SessionResult? = null,
    val lastSessionSaved: Boolean = false,
)

private const val TAG = "Riyaz"

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val library = SongLibrary(app)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val voiceStore = VoiceStore(app.filesDir)
    private val _state = MutableStateFlow(PlayerState(recent = library.recent(), voice = voiceStore.load(), learned = voiceStore.loadLearned(), habits = voiceStore.loadHabits()))

    // Your pitch on the song's timeline while singing; kept apart from [state] (it updates 30x a second).
    private val _liveSinging = MutableStateFlow<List<SungFrame>>(emptyList())
    val liveSinging: StateFlow<List<SungFrame>> = _liveSinging
    /** Measured delay (s) between the song and the mic while singing; the live view shifts your line by it. */
    private val _liveDelay = MutableStateFlow(0.2)
    val liveDelay: StateFlow<Double> = _liveDelay
    private var singJob: Job? = null
    private var melodyJob: Job? = null
    private val sessionFrames = ArrayList<SungFrame>()
    val state: StateFlow<PlayerState> = _state

    // Kept separate from [state] so the 20-per-second playhead updates only redraw the playhead.
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private var searchJob: Job? = null
    private var openJob: Job? = null
    private var analysisJob: Job? = null
    private val analyzer = SongAnalyzer(app)
    private var prepareJob: Job? = null
    private var lyricsJob: Job? = null
    private val separator = VocalSeparator(app)

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
        // Phase 6: re-fit the key whenever something it depends on changes
        viewModelScope.launch {
            _state.map { s ->
                listOf(s.song?.id, s.song?.title, s.song?.bpmFactor, s.song?.fitBase, s.karaoke.melody, s.karaoke.backingReady,
                    s.karaoke.lyrics, s.analysis, s.analysisError, s.voice, s.learned, s.habits)
            }.distinctUntilChanged().collect { refit() }
        }
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

    /** Tuner / voice test: the microphone would hear the speaker, so playback pauses. */
    fun openTool(t: Tool) {
        pause()
        _state.update { it.copy(tool = t) }
    }

    fun closeTool() = _state.update { it.copy(tool = null) }

    fun saveVoiceProfile(p: VoiceProfile) {
        viewModelScope.launch(Dispatchers.IO) { voiceStore.save(p) }
        _state.update { it.copy(voice = p) }
    }

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
        if (_state.value.karaoke.singing) stopSinging()
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
                analysis = null, analyzing = null, analysisError = null, karaoke = KaraokeState())
        }
        startAnalysis(song)
        attachBacking(song)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { LyricsStore.load(library.dir(song)) }
            if (saved != null) updateKaraoke(song) { it.copy(lyrics = saved) }
        }
    }

    // ------------------------------------------------------------------ karaoke

    private fun updateKaraoke(song: Song, change: (KaraokeState) -> KaraokeState) =
        _state.update { if (it.song?.id == song.id) it.copy(karaoke = change(it.karaoke)) else it }

    private suspend fun attachBacking(song: Song) {
        val f = VocalSeparator.backingFile(library.dir(song))
        val ok = f.exists() && withContext(Dispatchers.IO) { NativeEngine.nativeLoadBacking(f.absolutePath) }
        NativeEngine.nativeSetVocalGain(if (ok) song.vocalLevel else 1f)
        updateKaraoke(song) { it.copy(backingReady = ok) }
        if (ok) ensureMelody(song)
    }

    // ------------------------------------------------------------------ singing (Phase 5)

    /** Pitch-tracks the singer in the song (original - instrumental) once, and keeps it. */
    private fun ensureMelody(song: Song) {
        if (melodyJob?.isActive == true) return
        melodyJob = viewModelScope.launch {
            val dir = library.dir(song)
            val saved = withContext(Dispatchers.IO) { Melody.load(dir) }
            if (saved != null) { updateKaraoke(song) { it.copy(melody = saved) }; return@launch }
            updateKaraoke(song) { it.copy(melodyProgress = 0f) }
            val poll = launch { while (isActive) { updateKaraoke(song) { it.copy(melodyProgress = NativeEngine.nativeMelodyProgress()) }; delay(200) } }
            try {
                val t0 = SystemClock.elapsedRealtime()
                val midi = withContext(Dispatchers.Default) {
                    NativeEngine.nativeComputeMelody(library.pcmFile(song).absolutePath,
                        VocalSeparator.backingFile(dir).absolutePath, song.sampleRate)
                }
                poll.cancel()
                if (midi == null) { updateKaraoke(song) { it.copy(melodyProgress = null) }; return@launch }
                val m = Melody(midi)
                withContext(Dispatchers.IO) { Melody.save(dir, m) }
                Log.i(TAG, "melody for ${song.id}: ${midi.size} frames in ${SystemClock.elapsedRealtime() - t0} ms, range ${m.range()}")
                updateKaraoke(song) { it.copy(melody = m, melodyProgress = null) }
            } finally {
                poll.cancel()
            }
        }
    }

    fun setLearnFromSession(on: Boolean) = _state.value.song?.let { s -> updateKaraoke(s) { it.copy(learn = on) } }

    private fun headphonesConnected(): Boolean {
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val phones = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        return outs.any { it.type in phones }
    }

    /** Mic on: follows your voice on the song's timeline until [stopSinging]. Needs RECORD_AUDIO. */
    fun startSinging() {
        val song = _state.value.song ?: return
        if (singJob?.isActive == true) return
        sessionFrames.clear()
        _liveSinging.value = emptyList()
        updateKaraoke(song) { it.copy(singing = true, headphones = headphonesConnected(), lastSession = null, lastSessionSaved = false) }
        val tracker = PitchTracker()
        singJob = viewModelScope.launch {
            if (!tracker.start()) {
                updateKaraoke(song) { it.copy(singing = false, error = "Couldn't open the microphone") }
                return@launch
            }
            val t0 = SystemClock.elapsedRealtime()
            try {
                var polled = 0; var voiced = 0; var lastLog = 0L; var lastDelayCheck = 0L
                while (isActive) {
                    val frames = tracker.poll()
                    polled += frames.size; voiced += frames.count { it.voiced }
                    if (SystemClock.elapsedRealtime() - lastLog > 3000) {
                        Log.d(TAG, "singing: $polled readings, $voiced voiced, playing=${_state.value.playing}, kept ${sessionFrames.size}")
                        lastLog = SystemClock.elapsedRealtime()
                    }
                    if (_state.value.playing && frames.isNotEmpty()) {
                        // place each reading on the song's timeline: the song position now, minus its age
                        val nowSong = NativeEngine.nativePosition().toDouble() / song.sampleRate
                        val nowMs = SystemClock.elapsedRealtime() - t0
                        for (f in frames) {
                            val m = f.midi() ?: continue
                            sessionFrames += SungFrame(nowSong - (nowMs - f.tMs) / 1000.0, m)
                        }
                        val cutoff = nowSong - 8.0
                        _liveSinging.value = sessionFrames.takeLastWhile { it.songSec > cutoff }
                        // re-measure the delay every ~3 s from the last 15 s of singing
                        val mel = _state.value.karaoke.melody
                        if (mel != null && sessionFrames.size > 150 && SystemClock.elapsedRealtime() - lastDelayCheck > 3000) {
                            lastDelayCheck = SystemClock.elapsedRealtime()
                            val recent = sessionFrames.takeLastWhile { it.songSec > nowSong - 15.0 }
                            val key = song.semitones + song.cents / 100f
                            _liveDelay.value = withContext(Dispatchers.Default) {
                                SessionAnalysis.estimateDelay(mel, recent, key, SessionAnalysis.estimateOctave(mel, recent, key))
                            }
                        }
                    }
                    delay(33)
                }
            } finally {
                tracker.stop()
            }
        }
    }

    /** Mic off: compares your singing with the melody and (if learning is on) updates your voice profile. */
    fun stopSinging() {
        val song = _state.value.song ?: return
        singJob?.cancel()
        singJob = null
        val k = _state.value.karaoke
        val melody = k.melody
        val frames = ArrayList(sessionFrames)
        updateKaraoke(song) { it.copy(singing = false) }
        if (melody == null || frames.isEmpty()) return
        viewModelScope.launch {
            val key = song.semitones + song.cents / 100f
            val result = withContext(Dispatchers.Default) { SessionAnalysis.analyse(melody, frames, key) }
            // keep the raw readings of the last session (local only) so the analysis can be checked
            withContext(Dispatchers.IO) {
                runCatching {
                    java.io.File(library.dir(song), "last_session.csv").printWriter().use { w ->
                        w.println("songSec,midi,key=$key")
                        frames.forEach { w.println("%.3f,%.3f".format(it.songSec, it.midi)) }
                    }
                }
            }
            Log.i(TAG, "sing session: ${frames.size} frames, ${result.sungNotes.size}/${result.notes.size} notes, " +
                "accuracy ${(result.accuracy * 100).roundToInt()}%, octave ${result.octave}, delay ${"%.2f".format(result.delaySec)} s")
            val save = k.learn && result.enough
            if (save) {
                val range = melody.range()
                val shift = key + 12 * result.octave
                val pref = SongPreference(song.id, song.title, key, result.octave,
                    range?.first?.plus(shift), range?.second?.plus(shift), result.accuracy, System.currentTimeMillis())
                val learned = _state.value.learned.plus(result, pref)
                withContext(Dispatchers.IO) { voiceStore.saveLearned(learned) }
                _state.update { it.copy(learned = learned) }
            }
            updateKaraoke(song) { it.copy(lastSession = result, lastSessionSaved = save) }
        }
    }

    fun dismissSessionResult() = _state.value.song?.let { s -> updateKaraoke(s) { it.copy(lastSession = null) } }

    fun openKaraoke() {
        val song = _state.value.song ?: return
        _state.update { it.copy(showKaraoke = true) }
        val k = _state.value.karaoke
        if (k.lyrics == null && !k.lyricsLoading && k.lyricsMessage == null) {
            val q = LyricsService.queryFor(song.title, song.artist)
            fetchLyrics(listOfNotNull(q.title, q.artist).joinToString(" - "))
        }
    }

    fun closeKaraoke() {
        if (_state.value.karaoke.singing) stopSinging()
        _state.update { it.copy(showKaraoke = false) }
    }

    /** Makes the instrumental (downloads the model the first time). Runs in the background. */
    fun prepareKaraoke() {
        val song = _state.value.song ?: return
        if (prepareJob?.isActive == true) return
        updateKaraoke(song) { it.copy(preparing = Busy("Getting ready…", null, null), error = null) }
        prepareJob = viewModelScope.launch {
            val job = coroutineContext[Job]
            val cancelled = { job?.isCancelled == true }
            try {
                withContext(Dispatchers.IO) {
                    if (!separator.modelReady) {
                        updateKaraoke(song) { it.copy(preparing = Busy("Downloading the vocal remover (30 MB, once)…", null, 0f)) }
                        separator.ensureModel({ p -> updateKaraoke(song) { s -> s.copy(preparing = s.preparing?.copy(progress = p)) } }, cancelled)
                    }
                    updateKaraoke(song) { it.copy(preparing = Busy("Removing the vocals…", "Takes a few minutes, once per song. You can keep using the app.", 0f)) }
                    val t0 = SystemClock.elapsedRealtime()
                    separator.separate(
                        library.pcmFile(song), song.sampleRate, VocalSeparator.backingFile(library.dir(song)),
                        { p -> updateKaraoke(song) { s -> s.copy(preparing = s.preparing?.copy(progress = p)) } }, cancelled,
                    )
                    Log.i(TAG, "karaoke track for ${song.id}: ${SystemClock.elapsedRealtime() - t0} ms")
                }
                // if this song is still open, switch to karaoke straight away (vocals off)
                if (_state.value.song?.id == song.id) {
                    updateSong { it.copy(vocalLevel = 0f) }
                    _state.value.song?.let { attachBacking(it) }
                }
                updateKaraoke(song) { it.copy(preparing = null) }
            } catch (e: CancellationException) {
                updateKaraoke(song) { it.copy(preparing = null) }
            } catch (e: Throwable) {
                Log.e(TAG, "separation failed", e)
                updateKaraoke(song) { it.copy(preparing = null, error = e.message ?: "Couldn't make the karaoke track") }
            }
        }
    }

    fun cancelPrepareKaraoke() { prepareJob?.cancel() }

    fun setVocalLevel(v: Float) {
        updateSong { it.copy(vocalLevel = v.coerceIn(0f, 1f)) }
        if (_state.value.karaoke.backingReady) NativeEngine.nativeSetVocalGain(v.coerceIn(0f, 1f))
    }

    fun setLyricsQuery(q: String) = _state.value.song?.let { s -> updateKaraoke(s) { it.copy(lyricsQuery = q) } }

    /** Looks lyrics up on LRCLIB. [query] is "title - artist" (artist optional). */
    fun fetchLyrics(query: String = _state.value.karaoke.lyricsQuery) {
        val song = _state.value.song ?: return
        lyricsJob?.cancel()
        updateKaraoke(song) { it.copy(lyricsLoading = true, lyricsMessage = null, lyricsQuery = query) }
        lyricsJob = viewModelScope.launch {
            try {
                val parts = query.split(" - ", limit = 2)
                val found = withContext(Dispatchers.IO) {
                    LyricsService.fetch(LyricsService.Query(parts[0].trim(), parts.getOrNull(1)?.trim()?.ifBlank { null }), song.durationMs / 1000.0)
                }
                if (found != null) withContext(Dispatchers.IO) { LyricsStore.save(library.dir(song), found) }
                updateKaraoke(song) {
                    it.copy(lyrics = found ?: it.lyrics, lyricsLoading = false,
                        lyricsMessage = if (found == null) "No lyrics found. Try a different title or artist." else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                updateKaraoke(song) { it.copy(lyricsLoading = false, lyricsMessage = "Couldn't reach the lyrics service.") }
            }
        }
    }

    /** Tap-along sync for lyrics without timings (covers, live versions, indie songs). */
    fun startLyricsSync() {
        val song = _state.value.song ?: return
        updateKaraoke(song) { it.copy(syncing = true, syncNext = 0) }
        seekTo(0)
        play()
    }

    fun tapLyricLine() {
        val song = _state.value.song ?: return
        val k = _state.value.karaoke
        val lyrics = k.lyrics ?: return
        if (!k.syncing || k.syncNext >= lyrics.lines.size) return
        val t = _positionMs.value / 1000.0
        val lines = lyrics.lines.mapIndexed { i, l ->
            when {
                i < k.syncNext -> l
                i == k.syncNext -> l.copy(time = t)
                else -> LyricLine(l.text, null)
            }
        }
        val done = k.syncNext + 1 >= lines.size
        val updated = lyrics.copy(lines = lines, source = if (done) "lrclib+tap" else lyrics.source)
        updateKaraoke(song) { it.copy(lyrics = updated, syncNext = k.syncNext + 1, syncing = !done) }
        if (done) viewModelScope.launch(Dispatchers.IO) { LyricsStore.save(library.dir(song), updated) }
    }

    fun stopLyricsSync() {
        val song = _state.value.song ?: return
        val k = _state.value.karaoke
        // keep what was tapped so far
        k.lyrics?.let { l -> viewModelScope.launch(Dispatchers.IO) { LyricsStore.save(library.dir(song), l) } }
        updateKaraoke(song) { it.copy(syncing = false) }
    }

    // ------------------------------------------------------------------ auto-fit (Phase 6)

    private fun computeFit(s: PlayerState): FitState? {
        val song = s.song ?: return null
        val range = s.karaoke.melody?.range()
        val und = SongUnderstanding(
            language = Understanding.language(s.karaoke.lyrics?.lines?.map { it.text } ?: emptyList(), song.title),
            singer = Understanding.singer(range?.third),
            pace = Understanding.pace(s.analysis?.bpm?.times(song.bpmFactor)),
            rangeLow = range?.first, rangeHigh = range?.second, center = range?.third,
        )
        val yours = effectiveRange(s.voice, s.learned)
        val needs = when {
            yours == null -> "Do the range test (My voice, on the home screen) and Riyaz will fit songs to your voice."
            range == null && s.karaoke.backingReady -> "Working out the tune…"
            range == null -> "Make the karaoke track once (Karaoke → Remove vocals) and Riyaz will fit the key to your voice."
            s.analysis == null && s.analysisError == null -> "Working out the chords…"
            else -> null
        }
        if (needs != null || yours == null || range == null) return FitState(und, null, needs)
        val (low, high) = yours
        val center = s.voice?.center?.takeIf { it in low..high } ?: ((low + high) / 2f)
        val sug = KeyFit.suggest(range.first, range.second, range.third, low, high, center,
            s.analysis?.segments ?: emptyList(), s.habits.bias())
        return FitState(und, sug, null)
    }

    private fun refit() {
        val s = _state.value
        val fit = computeFit(s)
        _state.update { it.copy(fit = fit) }
        val song = s.song ?: return
        val sug = fit?.suggestion ?: return
        if (song.fitBase != null) return
        // First fit for this song: fill it in, unless you've already picked a key or capo yourself.
        if (song.semitones == 0 && song.cents == 0 && song.capo == 0) {
            Log.i(TAG, "auto-fit ${song.id}: key ${sug.shift} capo ${sug.capo} (base ${sug.base}) - ${sug.reason}")
            updateSong { it.copy(semitones = sug.shift, capo = sug.capo, fitBase = sug.base) }
            if (sug.shift == sug.base) recordHabit(song.id, sug.base, sug.shift)
        } else {
            updateSong { it.copy(fitBase = sug.base) }
            recordHabit(song.id, sug.base, song.semitones)
        }
    }

    private fun recordHabit(songId: String, suggested: Int, chosen: Int) {
        val h = _state.value.habits.record(songId, suggested, chosen)
        _state.update { it.copy(habits = h) }
        viewModelScope.launch(Dispatchers.IO) { voiceStore.saveHabits(h) }
    }

    /** Uses the suggested key and capo. */
    fun applyFit() {
        val sug = _state.value.fit?.suggestion ?: return
        updateSong { it.copy(semitones = sug.shift, cents = 0, capo = sug.capo, fitBase = sug.base) }
    }

    /** Back to the recording's own key, no capo. */
    fun useOriginalKey() = updateSong { it.copy(semitones = 0, cents = 0, capo = 0) }

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
        // your own key choices on fitted songs teach Riyaz your habit
        if (song.fitBase != null && updated.fitBase != null && updated.semitones != song.semitones) recordHabit(updated.id, updated.fitBase, updated.semitones)
        // Keep the Recent list in step, so it never shows stale key/speed.
        _state.update { s -> s.copy(song = updated, recent = s.recent.map { if (it.id == updated.id) updated else it }) }
        viewModelScope.launch(Dispatchers.IO) { library.save(updated) }
    }

    override fun onCleared() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        NativeEngine.nativeUnload()
    }
}
