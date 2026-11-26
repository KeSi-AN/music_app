# Your music app: build plan

See `TEARDOWN.md` for what this is based on. Working name: **Riyaz**. You can rename it any time.

## What it does

1. **Search any song** (or paste a YouTube link, or share it from the YouTube app). It gets the audio and works out **key, BPM, beats and chords**, the way Chord AI does. That works for Hindi, Bengali and indie songs that Ultimate Guitar doesn't have.
2. **Karaoke:** removes the vocals, shows synced lyrics, and plays in **any key** (pitch up or down without changing speed).
3. **Knows your voice:** a quick range test at the start, then it keeps learning from every karaoke session.
4. **Auto-fit:** when you open a new song it detects language, type, original singer and vocal range, then **suggests the key shift, capo and chord shapes** that suit *you*, with a one-line reason ("−3: the chorus peaks at G4, your comfortable top is E4"). If you change the suggestion, it learns from that.
5. **Songbook:** chord sheets you paste, share in or write. Transpose, simplify, autoscroll, setlists.
6. **Tuner.**

## Key decisions

| Decision | Choice | Why |
|---|---|---|
| Platform | **Android only, native Kotlin + Jetpack Compose, with C++ (NDK) for audio** | Same as Chord AI. Heavy audio and ML need native code; Flutter would just be a wrapper around that. |
| Backend | **None.** Everything runs on the phone; no account. | Your voice data never leaves the phone. Nothing to pay for. |
| Pitch / speed change | **Signalsmith Stretch** (MIT, C++), what UG uses | Best quality free option; independent pitch and tempo |
| Audio I/O | **Oboe** (low-latency mic and playback) + MediaCodec / FFmpeg decode | Needed for live singing feedback |
| YouTube audio | **NewPipeExtractor** (Java, GPL-3, fine for a personal app). Fallback: yt-dlp via Chaquopy, as Chord AI does. | No Python unless we need it |
| Vocal removal | **HT-Demucs** (MIT) on the phone, cached per song | Chord AI proves it runs on a phone |
| Beats | **beat_this** (MIT, TFLite), the model Chord AI ships | Known to work on mobile |
| Chords | **Decided in a test spike:** BTC transformer (MIT) as ONNX vs a Chordino-style chroma+HMM, judged on 5 of *your* songs against Chord AI | Chord accuracy on Indian music is the biggest unknown |
| Key | Duration-weighted chroma vs key profiles, cross-checked with the chords | Simple, explainable |
| Your voice pitch | YIN/MPM in C++ live; **CREPE-tiny** (MIT) for offline accuracy | Same tracker also runs the tuner |
| Lyrics | **LRCLIB** (free, open, synced LRC). Later: Whisper fallback + alignment. | No scraping |
| Language | **ML Kit Language ID** (on the phone; handles romanized Hindi) on title + lyrics; Whisper language detection on the vocal track as a fallback | |
| Song "type" | **YAMNet** (Apache-2, AudioSet classes include *Music of Bollywood*, *Carnatic*, *Pop*, *Rock*…) + original singer's voice (median pitch of the vocal track) | |
| Storage | Room (SQLite). Audio and stems in app storage; profile export/backup to a file. | |

## How the voice learning works (the new part)

Plain statistics you can inspect, not a black-box model.

**Your profile** keeps:
- Your **comfortable low and high** notes and your **centre** (tessitura).
- The **extremes** you can reach.
- A **preference offset per context** (language, type, original singer male/female).

It's built from:
- **The range test** (one minute): glide low, glide high, sing a comfortable phrase. That gives the starting values.
- **Every karaoke session**, using headphones. The app tracks the pitch of *your* voice frame by frame and compares it with the song's melody at the chosen key.
  - Notes held steady and in tune count as *comfortable*.
  - Notes at the edges that wobble, go flat or are dropped count as *strain*.
  - The profile updates with more weight on recent sessions, so it follows your voice as it changes.
  - **Only the pitch numbers are stored, never a recording**, unless you choose to record.
- **Your overrides:** if it suggests −3 and you move it to −4, that difference teaches the preference offset for that kind of song.

**Fitting a new song:**
1. Separate the vocals, pitch-track them, and get the song's melody range (low 5%, high 95%) and centre, weighted by how long each note lasts.
2. Try every shift from −7 to +7, **plus an octave flip** (a man singing a woman's song, or the reverse). Score each one:
   - a penalty for notes above your comfortable high or below your comfortable low (squared, so big misses hurt more);
   - distance from your centre;
   - a small cost for straying from the original key;
   - **how hard the chords are to play in that key with the best capo**;
   - plus your learned preference offset.
3. Show the best one with its reason, and two alternatives.

## Phases

Each phase ends with something you can use on your phone.

| # | Phase | You get |
|---|---|---|
| **0** | Project skeleton + audio engine (Oboe + Signalsmith) | Open any MP3 or video on the phone and play it in any key and at any speed |
| **1** | Song input: YouTube search, paste link, share from the YouTube app; audio cached | Any YouTube song → key-shifted playback |
| **2** | Song analysis: **chord model test spike first**, then key/BPM/beats/chords; chord timeline + diagrams; transpose/capo/simplify | Chord AI's core, free and without limits |
| **3** | Karaoke: Demucs vocal removal, LRCLIB synced lyrics, karaoke screen | Real karaoke in any key |
| **4** | Pitch tracker → **tuner** + **voice range test** | Tuner, and your first voice profile |
| **5** | Live voice learning during karaoke (your pitch drawn over the song's melody, profile updates) | It starts to know your voice |
| **6** | Auto-fit: language/type/singer detection + key/capo suggestion + learning from your overrides | The "it just picks the right key" moment |
| **7** | Songbook: paste or share chord sheets (chords-over-lyrics and ChordPro), editing, setlists, autoscroll | Your own chord library |
| **8** | Polish: backup/restore, Whisper lyrics fallback, play-along check | |

## Risks (honest)

- **Chord accuracy on Hindi/Bengali songs.** Open models are trained mostly on Western pop. That's why Phase 2 starts with a spike against Chord AI on your songs. If quality is poor, we fall back to "chords as a draft you edit".
- **YouTube extraction breaks** every few months when YouTube changes things. NewPipeExtractor updates fast; local files always work.
- **Demucs speed and battery.** Expect roughly a minute or more per song on the Edge 50 Neo, done once and cached. To be measured in Phase 3.
- **"Chords for any song" from a database** like UG's isn't possible without breaking their terms. Our answer is analysing the audio + your own songbook.

## Not doing

Accounts, cloud, ads, social features, scraping UG.
