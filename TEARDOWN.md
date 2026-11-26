# Music apps: teardown (Ultimate Guitar, Chord AI, GuitarTuna)

**Source:** pulled over ADB from the Moto Edge 50 Neo on 2026-11-26.

| App | Package | Version |
|---|---|---|
| Ultimate Guitar | `com.ultimateguitar.tabs` | 7.3.40 (4009416) |
| Chord AI | `com.chordai` | 2.8.19 (20819) |
| GuitarTuna | `com.ovelin.guitartuna` | 7.106.0 (174799) |

**Method:**
- **Static read:** `aapt2` (manifest, permissions, resources), `dexdump` (class names), and a plain strings pass over the native libraries, UG's JavaScript bundle and GuitarTuna's Unity metadata.
- **Live read:** screenshots and `uiautomator` dumps, in `*/live/`.
  - UG stops at a sign-in wall ("Continue with Google / Facebook / E-mail"). No skip option, so I didn't create an account.
  - Chord AI: I opened a song already in your history ("Uska Hi Bana").
  - GuitarTuna opens on a 7-day-trial upsell, and its Unity screens can't be read as text, so I stopped there.
- **Not done:** no decompiling, no network capture, no attempt on encrypted files, nothing bought.

Raw dumps are in `*/extract/`.

---

## 1. One-line summary of each

| | Ultimate Guitar | Chord AI | GuitarTuna |
|---|---|---|---|
| **What it really is** | A huge **server database** of user-submitted chords and tabs, plus a player | An **on-device AI** that listens to any audio and works out chords, beats and key | A **pitch tracker** (tuner) plus chord and song games |
| **Built with** | React Native (Hermes JS) + C++ audio modules | Kotlin/Compose + C++ + **Python running on the phone** (Chaquopy) | Unity (C#, il2cpp) + Yousician's own C++ audio engine |
| **Chords come from** | Its servers (`api.ultimate-guitar.com`) | **Analysing the audio itself** | Its own song catalogue |
| **Works on Hindi/Bengali indie songs?** | Only if someone uploaded them | **Yes, works on any audio.** Your history is all Hindi/Bengali (Mack Vocals, Somlata). | No |

**The main takeaway:** UG's value is its *database*, and we can't copy it. Chord AI's value is its *technique*, and we can rebuild that from open-source parts. Chord AI is also the app you already use.

---

## 2. Ultimate Guitar

**Stack.** React Native 0.88 with the Hermes engine; the whole UI is in a 23 MB `index.android.bundle`. Native libraries:
- `libsignalsmithstretch.so`: Signalsmith Stretch, an **MIT-licensed** pitch and time stretcher. This is how UG changes pitch and speed on backing tracks.
- `liboboe.so`: Google's low-latency audio.
- `libtuner_bridge_jni.so`: its tuner.
- `libavcodec` / `libavformat`: FFmpeg decoding.
- `libreact-native-audio-api.so`: a Web-Audio-style audio graph.
- `libpairipcore.so`: Google "Play Integrity Protect". About 30 assets with random names are encrypted by this.

**Features seen in the code** (names taken from the bundle's string table):
- **Transpose:** `transposeSemitones`, `setTranspose`, `ComputedTranspose`, and a separate `pianoTransposer`. Also **capo** (`createCapo`, `capoFormat`, `noCapo`) and **Simplify chords** (`chordSimplifierSettingsTab`, `simplifiedChords`).
- **Pitch on audio:** `setAudioTrackPitch`, `setGeneralPitch`, `setPartPitch`, `pitchRatio`.
- **Autoscroll / "Smartscroll":** `smartscrollEngine` follows your playing.
- **Karaoke:** `KaraokeScreen`, `KaraokeSongParser`, `KaraokeSongLyricsLine`, `KaraokeSongLyricsSyllable` (lyrics timed per **syllable**), `KaraokeCanvas`, `KaraokeSongBeatStatus`, `removeVocal`.
- **Singing range, the closest thing to what you want:**
  - A profile setting `singingVoice` with only **male / female**.
  - Analytics events `TabSingRangeClick`, `TabSingVoiceTypeClick` and `TabSingTryAgainClick`.
  - `voiceGuide`, `voiceThreshold` and `getMidiNumberFromVoiceCell`.

  So UG has a "Sing" tab that shows a song's vocal range against a coarse voice type and lets you *try again*. **It does not learn from you over time.** It is a one-off pick of male or female plus a range check.
- **Practice:** a metronome, speed control, section loops, **Autograde** (it listens to you play and grades you), and backing tracks.
- **YouTube:** an embedded player synced to the tab (`hasInteractiveSyncedYoutubeVideo`, `YoutubeStylePlayer`).

**Takeaway for us:**
- Signalsmith Stretch is the proven, free choice for good-quality pitch shifting. We'll use it.
- UG's chord database is private. Its API is undocumented, needs login, and scraping it breaks their terms. Copying it is off the table.

---

## 3. Chord AI (most relevant)

**Stack.** Kotlin + Jetpack Compose + C++ (`libchord_ai_cpp.so`, using Eigen), with **Python 3.11 embedded via Chaquopy**. Everything heavy runs **on the phone**.

### What runs on the phone

| Model / file | What it does |
|---|---|
| `model_december2023_chords` (10 MB) | Chord classifier on mel spectrograms |
| `model_december2023_chord_seq` (25 MB) | Chord *sequence* model that smooths chord changes over time |
| `model_december2023_beats` (9 MB) + `beat_this_small` (9.7 MB `.tflite`) | Beat and downbeat tracking. **beat_this** is an open-source JKU Linz model (MIT). |
| `position_…` | Chord **voicing / fret position** |
| `model_audio_tagging_pack` (24 MB, MobileNetV1 mAP) | AudioSet-style tagging (instrument, genre, vocals present) |
| `whisper-base` (13 MB, asset pack) | **Speech-to-lyrics** when no lyrics are found |
| `libhtdemucs_native.so` | **HT-Demucs stem separation** (vocals / drums / bass / other), streaming STFT → model → iSTFT, **on the phone** |
| `libLiteRt.so` + GPU accelerator | TensorFlow Lite (LiteRT) runtime for all of the above |

The models are stored as `.dead` files: **encrypted zips** (`EncryptedNetworkModel`, password-protected `args.json` / `labels_info.json`). I didn't try to open them.

### Pipeline (from class names and JNI symbols)

1. **Getting the audio** (`media_manager/media_tools`):
   - `YoutubeSearchActivity` + `YoutubeIdParser`.
   - An embedded YouTube player (`androidyoutubeplayer`) that the analysis is synced to (`YoutubeSlaving`, `YoutubeClock`, `YoutubeDelayProbe`).
   - **`yt-dlp 2026.7.4` bundled as a Python package** (`MediaLoaderPyJava`) to pull the audio.
   - `MusicLinkResolver` / `TrackMatcher` turn a Spotify or Apple link into a YouTube match.
   - Also supported: microphone, files, any URL.
2. **Decode and resample:** FFmpegKit, then libresample4j.
3. **Analysis:** `CPPInterface.processAudio` / `feedDataAndPredict` gives chords + beats + `getRealTimeKeyCpp` (key) + `getWindowTempi` (BPM), plus `songHasVoicingsLogits`.
4. **Music theory in C++:**
   - `transposeChordName` and `getAppropriateNoteNameInKey` (correct sharp/flat spelling for the key).
   - A `capo` / `capo_objective` search that picks a capo fret so the chord shapes are easy.
   - The data files `chords_list.json`, `keys_list.json` and `tunings_list.json` cover guitar, bass, ukulele, banjo and mandolin.
5. **Lyrics** (`lyrics/*`):
   - Scrapes AZLyrics (`LyricsFromAzLyrics`) or calls its own API.
   - Otherwise runs **Whisper**.
   - Then aligns the words to the audio with **DTW** + edit distance + an n-gram decoder.
6. **Separation:** `StemSeparation` → `HTDemucsSeparator`. The live screen says **"Mixer unavailable with YouTube"**, so stems only work on downloaded or local audio.
7. **Saving:** `.chordai` files are encrypted zips holding `transcription.json`. There's also a PDF export and Google Drive sync.

### Live screen (`chordai/live/02–05`)

- **Main view:** a waveform timeline, the detected **key ("Am")** and **BPM 96**, current and next chord diagrams, a **Basic / Precise** chords toggle, a **Voicings** toggle and a **Grid view**.
- **Bottom bar:** Pitch / Speed / Loop, plus a **Stem mixer**.
- **Pitch dialog:** three separate controls.
  - **Capo:** move the shapes.
  - **Chords only:** transpose the chord names, audio unchanged.
  - **Audio and chords:** pitch-shift the song *and* the chords together. **This is the karaoke key change you want.**
- **Lyrics** are behind PRO.

**What it lacks:** no singer profile, no idea of *your* voice, no automatic "best key for me", and no karaoke mode.

---

## 4. GuitarTuna

**Stack.** A Unity app (il2cpp). The real work is in `libYousicianNative.so`, a JUCE-based C++ engine with:
- `Yousician::Gen::PitchTracker`, `Tuner`, `PitchShifter`.
- `DnnOnsetEstimator`, run through **ONNX Runtime**.
- Amp and cabinet models.
- `FirstChordDetector` and `CampfireChordLearner`, with log messages like "Stable tonality matches first chord for capoIndex".

**Takeaways:**
- A good tuner is a **pitch tracker over a short window with a stable display**: YIN or MPM, then smoothing, then cents off the nearest note.
- The same pitch tracker is what we need to **listen to your voice**.
- Their "is the user playing chord X" check combines chroma with a small DNN for onsets. That's useful later for a "play-along" check, but not needed now.

---

## 5. What this means for your app

| You asked for | Closest existing app | Gap we fill |
|---|---|---|
| Chords and tabs for any song | UG (database) / Chord AI (from audio) | **Use Chord AI's approach.** It works on Hindi/Bengali songs that UG doesn't have. Add a personal songbook for text chords you paste or write. |
| Karaoke from YouTube or a link | Chord AI (YouTube + yt-dlp), UG (karaoke screen) | Combine them: download, remove vocals (Demucs), pitch-shift (Signalsmith), show synced lyrics |
| Transpose / pitch down | Chord AI's three-way Pitch dialog | Same, but **suggested for you** |
| Learns your voice and range over time | **Nobody.** UG only has male/female plus a range check. | **This is the new part:** a voice profile that improves from every karaoke session |
| Auto-detect language, type and key, then autofill chords and pitch | Chord AI detects key/BPM; its tagger hints at genre | Add language ID + vocal-range analysis of the song, then fit it to your profile |
