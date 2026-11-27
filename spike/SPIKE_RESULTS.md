# Phase 2 spike: chords, key and beats

Run on 2026-11-27 on the laptop.

## Setup

**Test songs:** three songs from your own Chord AI history:
- Uska Hi Bana (Mack Vocals cover)
- Kaa Karoon Sajni (Somlata)
- Naina Morey (Somlata)

**Audio:** downloaded from the same YouTube videos Chord AI used.

**Reference:** Chord AI's chords, read beat by beat from its Grid view on the phone (`scrape_chordai_grid.py`). Those files are kept locally and not committed, since they are another app's output.

**How it's scored:**
- Beats are lined up with DTW, because the beat trackers disagree on tempo.
- 2× and ½× beat rates are allowed.
- One global transposition is allowed.
- "Root" means the same chord root. "Majmin" means the same root *and* the same major/minor quality.

## Chord models

| Candidate | Root | Majmin | Notes |
|---|---|---|---|
| **BTC large vocabulary** (MIT, 170 chords) | **86.7%** | 83.3% | Also gives 7, maj7, m7, sus2/4, dim, aug, 6 |
| BTC major/minor (25 chords) | 86.5% | 84.5% | |
| Chroma templates + HMM (no model) | 38.9% | 18.3% | Not usable |

The side-by-side printouts match Chord AI almost bar for bar. Most disagreements are short transitions, or sus/7 versus plain triads.

**Naina Morey:** Chord AI's chords are **2 semitones lower** than the audio, which is really in G#m. So Chord AI was showing a capo-2 view, or a transpose that had been set. A setting like that is exactly the kind of preference Riyaz should learn (Phase 6).

## Beats

| Option | Time per song (laptop) | Tempo | Chord accuracy on its beats |
|---|---|---|---|
| beat_this final0 | 21–26 s | Double-time on slow songs | Baseline |
| beat_this small0 (what Chord AI ships) | 13–27 s in ONNX Runtime, even with 4 threads or int8 | Matches Chord AI's BPM | Same |
| **Classic onset + DP tracker** | **about 1–2 s** | Right, or ×2 | Within about 1% |

beat_this is too slow for the phone for now: shorter chunks still took about 11 s. → Use the classic tracker, with a ×2 / ÷2 button for tempo. beat_this can come back later as a background refinement.

## Key

Working out the key from the detected chords matched Chord AI on all 3 songs. A plain chroma key profile got 2 of 3 wrong.

## Phone pipeline (`portable.py`)

`portable.py` reproduces everything in plain numpy, using only methods that will go into C++:
- Kaiser-sinc resampling to 22,050 Hz.
- A frame-based CQT (Brown–Puckette): 144 bins, 24 per octave, hop 2048. It is within about 3% of librosa's level per bin.
- Log spectral-flux onset strength, autocorrelation tempo with a prior around 120 BPM, and the Ellis DP beat tracker.
- BTC in ONNX Runtime, with probabilities averaged per beat.
- Bar lines placed where chords change most often.

**End-to-end results vs Chord AI:**
- Uska Hi Bana: **96%** root
- Kaa Karoon Sajni: **90%** root
- Naina Morey: **75%** root

That's as good as the librosa reference.

## Model sizes and speed

- **BTC large ONNX:** 13.1 MB; 0.45 s for a 5-minute song on one laptop thread.
- **Exported models:** they match PyTorch to within 1e-5, with identical chord choices.

---

# Phase 3 spike: vocal removal and lyrics

Run on 2026-11-27.

## Vocal removal

**Data:** 10 clips from the MUSDB18 7-second test set, each with its true vocal and accompaniment tracks.

**Score:** SDR in dB (higher is better). The instrumental is the part that matters for karaoke.

| Model | Instrumental | Vocals | Laptop, 4 threads, 5-min song | Size |
|---|---|---|---|---|
| MDX Kim_Vocal_2 | 13.8 | 8.5 | about 5.5–7.5 min | 67 MB |
| MDX Voc_FT | 13.8 | 8.4 | about the same | 67 MB |
| **MDX UVR_MDXNET_9482** | **13.0** (13.8 with our own pipeline) | 7.7 (9.5) | **about 1.6–2 min** | **30 MB** |
| HT-Demucs (4 stems; what Chord AI ships) | 12.8 | 9.1 | about 8 min | 84 MB |
| Open-Unmix large | 12.1 | 6.9 | about 2 min | |
| MDX Inst_HQ_3 | 12.0 | 7.3 | slowest | 67 MB |
| Open-Unmix HQ | 10.9 | 5.6 | about 1 min | |

- **int8 quantisation** made MDX slower in ONNX Runtime: 3.1 s per chunk instead of 2.1.
- **→ Chosen: UVR_MDXNET_9482.** It's downloaded once from the UVR model release and checked against its SHA-256.

## Our own MDX pipeline

`mdx_portable.py` uses UVR's original trimmed-chunk scheme: chunks advance by `chunk − n_fft`, and only the middle of each is kept.
- **Quality:** 13.8 dB instrumental, 9.5 dB vocals.
- **Cost:** the same as running with no overlap.

The C++ port (`app/.../separation.cpp`) was checked on Windows with `spike/cpptest`:
- Spectrogram inputs match numpy to within 3e-5.
- Reconstructed vocals match to 1e-7.
- The instrumental file comes out exactly the song's length.

## Lyrics (LRCLIB)

Of 9 of your songs:
- **Synced lyrics (6):** the mainstream Telugu/Hindi songs.
- **Plain lyrics only (2):** the indie Somlata songs.
- **Covers:** they get the original's lyrics, but those are timed for a different recording.

So synced lyrics are used only when the recording lengths match within 3 s. Otherwise the plain text is shown, and you can sync it once by tapping along.
