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
