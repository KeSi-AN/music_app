"""Phase 2 spike: which chord recogniser agrees best with Chord AI on the user's own songs?

Reference: Chord AI's beat grid scraped from the phone (chordai_ref/<song>.json, one label per beat).
Candidates (all beat-synchronous on beat_this beats):
  A  BTC transformer, major/minor model (25 classes)       - github.com/jayg996/BTC-ISMIR19 (MIT)
  B  BTC transformer, large vocabulary (170 classes)
  C  chroma templates + HMM (no model; trivial to write in C++)
Scores (after aligning beat indices with the best constant offset):
  root   - same chord root (N counts as its own root)
  majmin - same root and major/minor quality, on beats where the reference is a plain maj/min chord
Usage: .venv/Scripts/python run_spike.py
"""
import json, re, sys, time
from pathlib import Path

import librosa
import numpy as np
import torch

np.float = float  # BTC (2019) still uses the removed numpy alias

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE / "ext" / "btc"))
from btc_model import BTC_model  # noqa: E402
from utils.hparams import HParams  # noqa: E402

SONGS = ["uska", "kaa_karoon", "naina_morey"]
SR = 22050
HOP = 2048
NOTES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
FLAT = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#", "Cb": "B", "Fb": "E", "E#": "F", "B#": "C"}

# ------------------------------------------------------------------ chord names

def parse(label):
    """Any chord name ('F#m7', 'A:min7', 'Asus2', 'N', '-') -> (root 0..11 or None, quality)."""
    if label is None:
        return None
    if label in ("N", "X", "-", ""):
        return (None, "N")
    m = re.match(r"^([A-G](?:#|b)?)(?::)?(.*)$", label)
    if not m:
        return (None, "N")
    root, q = m.group(1), m.group(2)
    root = FLAT.get(root, root)
    r = NOTES.index(root)
    q = q.split("/")[0]
    if q in ("", "maj", "7", "M7", "maj7", "6", "maj6", "add9", "9", "M9", "aug"):
        qual = "maj"
    elif q.startswith("min") or q.startswith("m") and not q.startswith("maj") or q in ("dim", "dim7", "hdim7"):
        qual = "min"
    elif "sus" in q:
        qual = "sus"
    else:
        qual = "maj"
    return (r, qual)

def name(p):
    if p is None or p[0] is None:
        return "N"
    return NOTES[p[0]] + ("m" if p[1] == "min" else ("sus" if p[1] == "sus" else ""))

# ------------------------------------------------------------------ reference

def load_ref(song):
    beats = json.load(open(HERE / "chordai_ref" / f"{song}.json"))["beats"]
    out, cur = [], (None, "N")
    for b in beats:
        if b is not None:
            cur = parse(b)
        out.append(cur)
    return out

# ------------------------------------------------------------------ features

def btc_features(y):
    """Exactly BTC's own feature pipeline (10 s CQT chunks, log magnitude)."""
    chunk = SR * 10
    feats = []
    for s in range(0, len(y), chunk):
        part = y[s:s + chunk]
        if len(part) < HOP:
            break
        feats.append(librosa.cqt(part, sr=SR, n_bins=144, bins_per_octave=24, hop_length=HOP))
    return np.log(np.abs(np.concatenate(feats, axis=1)) + 1e-6)   # (144, frames)

FRAME_SEC = 10.0 / 108   # BTC's own time unit

def run_btc(feature, large):
    import yaml
    config = HParams(**yaml.safe_load(open(HERE / "ext" / "btc" / "run_config.yaml")))
    if large:
        config.feature["large_voca"] = True
        config.model["num_chords"] = 170
        ckpt = HERE / "ext" / "btc" / "test" / "btc_model_large_voca.pt"
    else:
        ckpt = HERE / "ext" / "btc" / "test" / "btc_model.pt"
    model = BTC_model(config=config.model)
    cp = torch.load(ckpt, map_location="cpu", weights_only=False)
    model.load_state_dict(cp["model"])
    model.eval()
    x = (feature.T - cp["mean"]) / cp["std"]
    T = config.model["timestep"]
    pad = (-x.shape[0]) % T
    x = np.pad(x, ((0, pad), (0, 0)))
    probs = []
    with torch.no_grad():
        xt = torch.tensor(x, dtype=torch.float32).unsqueeze(0)
        for i in range(x.shape[0] // T):
            h, _ = model.self_attn_layers(xt[:, i * T:(i + 1) * T, :])
            logits = model.output_layer.output_projection(h)   # (1, T, C)
            probs.append(torch.softmax(logits, -1).squeeze(0).numpy())
    probs = np.concatenate(probs)[: feature.shape[1]]
    if large:
        labels = []
        quals = ["min", "maj", "dim", "aug", "min6", "maj6", "min7", "minmaj7", "maj7", "7", "dim7", "hdim7", "sus2", "sus4"]
        for i in range(168):
            labels.append(NOTES[i // 14] + ":" + quals[i % 14])
        labels += ["X", "N"]
    else:
        labels = [n for r in NOTES for n in (r, r + ":min")] + ["N"]
    return probs, labels

def chroma_hmm(feature, beat_frames):
    """Baseline: fold CQT to 12 pitch classes, beat-average, match 24 triad templates, Viterbi."""
    mag = np.exp(feature)                       # back to linear
    mag = mag[: (mag.shape[0] // 24) * 24]
    chroma = mag.reshape(-1, 12, 2, mag.shape[1]).sum(axis=(0, 2))   # bins_per_octave 24: 2 bins per semitone
    chroma = np.log1p(100 * chroma)
    # CQT starts at C1, so bin 0 is C
    B = beat_sync(chroma.T, beat_frames)        # (beats, 12)
    B = B / (np.linalg.norm(B, axis=1, keepdims=True) + 1e-9)
    templ, labels = [], []
    for r in range(12):
        for q, iv in (("maj", (0, 4, 7)), ("min", (0, 3, 7))):
            t = np.zeros(12)
            t[[(r + i) % 12 for i in iv]] = 1
            templ.append(t / np.linalg.norm(t))
            labels.append((r, q))
    templ = np.array(templ)
    sim = B @ templ.T                           # (beats, 24)
    emit = np.exp(8 * sim)
    emit /= emit.sum(axis=1, keepdims=True)
    n = 24
    trans = np.full((n, n), (1 - 0.9) / (n - 1))
    np.fill_diagonal(trans, 0.9)
    logt = np.log(trans)
    dp = np.log(emit[0] + 1e-12)
    back = []
    for t in range(1, len(emit)):
        cand = dp[:, None] + logt
        back.append(cand.argmax(axis=0))
        dp = cand.max(axis=0) + np.log(emit[t] + 1e-12)
    path = [int(dp.argmax())]
    for b in reversed(back):
        path.append(int(b[path[-1]]))
    path.reverse()
    return [labels[i] for i in path], B

def beat_sync(frames, beat_frames):
    out = []
    for a, b in zip(beat_frames[:-1], beat_frames[1:]):
        b = max(b, a + 1)
        out.append(frames[a:b].mean(axis=0))
    return np.array(out)

def btc_to_beats(probs, labels, beat_times):
    bf = np.clip(np.round(np.asarray(beat_times) / FRAME_SEC).astype(int), 0, len(probs) - 1)
    P = beat_sync(probs, bf)
    return [parse(labels[i]) for i in P.argmax(axis=1)]

# ------------------------------------------------------------------ key

MAJOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MINOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])

def key_of(chroma_sum):
    best = None
    for r in range(12):
        for prof, q in ((MAJOR, "maj"), (MINOR, "min")):
            c = np.corrcoef(np.roll(prof, r), chroma_sum)[0, 1]
            if best is None or c > best[0]:
                best = (c, (r, q))
    return best[1]

def key_from_chords(seq):
    """Key from chord counts: tonic triad + its diatonic neighbours (simple, explainable)."""
    counts = {}
    for p in seq:
        if p and p[0] is not None:
            counts[p] = counts.get(p, 0) + 1
    best = None
    for r in range(12):
        for q in ("maj", "min"):
            if q == "maj":
                dia = [(r, "maj"), ((r + 5) % 12, "maj"), ((r + 7) % 12, "maj"), ((r + 9) % 12, "min"), ((r + 2) % 12, "min"), ((r + 4) % 12, "min")]
            else:
                dia = [(r, "min"), ((r + 5) % 12, "min"), ((r + 7) % 12, "min"), ((r + 3) % 12, "maj"), ((r + 8) % 12, "maj"), ((r + 10) % 12, "maj"), ((r + 7) % 12, "maj")]
            s = sum(counts.get(d, 0) + counts.get((d[0], "sus"), 0) for d in dia) + 0.5 * counts.get((r, q), 0)
            if best is None or s > best[0]:
                best = (s, (r, q))
    return best[1]

# ------------------------------------------------------------------ scoring

def dtw_align(ref, est, band_frac=0.15):
    """Monotonic alignment of two beat-label sequences whose tempos differ.
    Returns, for every reference beat, the estimated label it lines up with."""
    n, m = len(ref), len(est)
    INF = 1e9
    cost = np.full((n + 1, m + 1), INF)
    cost[0, 0] = 0.0
    step = np.zeros((n + 1, m + 1), dtype=np.int8)
    band = int(max(n, m) * band_frac) + 8
    for i in range(1, n + 1):
        jc = int(i * m / n)
        for j in range(max(1, jc - band), min(m, jc + band) + 1):
            a, b = ref[i - 1], est[j - 1]
            c = 0.0 if a == b else (0.4 if a[0] == b[0] else 1.0)
            o0, o1, o2 = cost[i - 1, j - 1], cost[i - 1, j] + 0.2, cost[i, j - 1] + 0.2
            if o0 <= o1 and o0 <= o2:
                cost[i, j], step[i, j] = c + o0, 0
            elif o1 <= o2:
                cost[i, j], step[i, j] = c + o1, 1
            else:
                cost[i, j], step[i, j] = c + o2, 2
    i, j, match = n, m, {}
    while i > 0 and j > 0:
        match.setdefault(i - 1, j - 1)
        k = step[i, j]
        if k == 0:
            i, j = i - 1, j - 1
        elif k == 1:
            i -= 1
        else:
            j -= 1
    return [est[match[i]] if i in match else (None, "N") for i in range(n)]

def transpose(seq, k):
    return [((p[0] + k) % 12, p[1]) if p and p[0] is not None else p for p in seq]

def signed(k):
    k %= 12
    return k - 12 if k > 6 else k

def likely_shifts(ref, est):
    """The two transpositions whose root histograms agree best (prunes the expensive DTW)."""
    h1, h2 = np.zeros(12), np.zeros(12)
    for p in ref:
        if p[0] is not None:
            h1[p[0]] += 1
    for p in est:
        if p[0] is not None:
            h2[p[0]] += 1
    sc = [np.dot(h1, np.roll(h2, k)) for k in range(12)]
    return [int(k) for k in np.argsort(sc)[-2:]]

def score(ref, est):
    """DTW-aligned beat agreement. Also tries 2x / 0.5x beat rate (trackers disagree on double time)
    and one global transposition (Chord AI may show capo shapes or a transpose the user set)."""
    best = None
    for factor in (1, 2, 0.5):
        e0 = [x for x in est for _ in range(2)] if factor == 2 else (est[::2] if factor == 0.5 else est)
        for k in likely_shifts(ref, e0):
            al = dtw_align(ref, transpose(e0, k))
            root = float(np.mean([a[0] == b[0] for a, b in zip(ref, al)]))
            mm = [(a == b) for a, b in zip(ref, al) if a[1] in ("maj", "min")]
            majmin = float(np.mean(mm)) if mm else 0.0
            if best is None or root > best["root"]:
                best = dict(root=root, majmin=majmin, factor=factor, shift=signed(k), al=al)
    return best

# ------------------------------------------------------------------ main

def main():
    from beat_this.inference import File2Beats
    f2b = File2Beats(checkpoint_path="final0", device="cpu", dbn=False)
    rows = []
    for song in SONGS:
        wav = HERE / "audio" / f"{song}.wav"
        y, _ = librosa.load(wav, sr=SR, mono=True)
        ref = load_ref(song)

        t0 = time.time(); beats, downbeats = f2b(str(wav)); t_beats = time.time() - t0
        t0 = time.time(); feat = btc_features(y); t_feat = time.time() - t0
        bf = np.clip(np.round(beats / (HOP / SR)).astype(int), 0, feat.shape[1] - 1)

        res = {}
        t0 = time.time(); pa, la = run_btc(feat, large=False); t_a = time.time() - t0
        res["A btc-majmin"] = (btc_to_beats(pa, la, beats), t_a)
        t0 = time.time(); pb, lb = run_btc(feat, large=True); t_b = time.time() - t0
        res["B btc-large"] = (btc_to_beats(pb, lb, beats), t_b)
        t0 = time.time(); seq_c, chroma_beats = chroma_hmm(feat, bf); t_c = time.time() - t0
        res["C chroma-hmm"] = (seq_c, t_c)

        ref_bpm = None
        bpm = 60 / np.median(np.diff(beats))
        print(f"\n== {song}: {len(y)/SR:.0f} s, beat_this {len(beats)} beats ({bpm:.1f} BPM, {t_beats:.1f} s), "
              f"Chord AI grid {len(ref)} beats; CQT {t_feat:.1f} s")
        print(f"   key: Chord AI's chords -> {name(key_from_chords(ref))}, chroma profile -> {name(key_of(chroma_beats.sum(0)))}")
        for k, (seq, t) in res.items():
            sc = score(ref, seq)
            kc = name(key_from_chords(seq))
            print(f"   {k:14s} root {sc['root']*100:5.1f}%  majmin {sc['majmin']*100:5.1f}%  "
                  f"(beats x{sc['factor']}, Chord AI = ours {sc['shift']:+d} st)  key {kc:4s}  {t:5.1f} s")
            rows.append((song, k, sc["root"], sc["majmin"]))
        # a readable side-by-side of a stretch of the song, model A after alignment
        sc = score(ref, res["A btc-majmin"][0])
        print("   ChordAI:", " ".join(name(ref[i]).ljust(4) for i in range(96, 128)))
        print("   BTC-A  :", " ".join(name(sc["al"][i]).ljust(4) for i in range(96, 128)))

    print("\n== mean over songs")
    for k in sorted({r[1] for r in rows}):
        rr = [r for r in rows if r[1] == k]
        print(f"   {k:14s} root {np.mean([r[2] for r in rr])*100:5.1f}%  majmin {np.mean([r[3] for r in rr])*100:5.1f}%")

if __name__ == "__main__":
    main()
