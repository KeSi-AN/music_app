"""Play-along check, tested on real songs before the phone.

For each song: BTC chords (the app's chord model, via portable.py) give the chord at each moment; the
app's native chroma code (cpptest/test_chroma.exe) gives what a microphone would hear; the app's chord
templates (same as PlayAlong.kt) decide whether that "matches". A good check says yes on the right chord
and no on wrong ones. Full mixes include vocals and drums, so this is harder than a guitar alone.
"""
import subprocess, sys, wave, os
import numpy as np
import portable as P

QUAL = {"maj": [0, 4, 7], "min": [0, 3, 7], "dim": [0, 3, 6], "aug": [0, 4, 8], "7": [0, 4, 7, 10], "maj7": [0, 4, 7, 11],
        "min7": [0, 3, 7, 10], "minmaj7": [0, 3, 7, 11], "maj6": [0, 4, 7, 9], "min6": [0, 3, 7, 9], "dim7": [0, 3, 6, 9],
        "hdim7": [0, 3, 6, 10], "sus2": [0, 2, 7], "sus4": [0, 5, 7]}
MINORISH = {"min", "dim", "min6", "min7", "minmaj7", "dim7", "hdim7"}

def template(root, q):
    t = np.zeros(12)
    for i in QUAL[q]:
        pc = root + i
        t[pc % 12] += 1; t[(pc + 7) % 12] += 0.25; t[(pc + 4) % 12] += 0.12
    t[root % 12] += 0.3
    return t

TRIADS = [((r, q), template(r, q)) for r in range(12) for q in ("maj", "min")]

def score(c, t):
    a, b = np.linalg.norm(c), np.linalg.norm(t)
    return 0.0 if a == 0 or b == 0 else float(c @ t / (a * b))

def best(c):
    return max(((k, score(c, t)) for k, t in TRIADS), key=lambda x: x[1])

def matches(c, root, q):
    (b, bs) = best(c)
    simple = (root, "min" if q in MINORISH else "maj")
    return b == simple or score(c, template(root, q)) >= bs - 0.03

def run(name):
    w = wave.open(f"audio/{name}.wav")
    y = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    sr = w.getframerate()
    # reference chords, one per BTC frame
    y22 = y if sr == P.SR else P.resample(y, sr)
    cq = P.CQT()(y22)
    probs = P.Chords().probs(cq)
    labels = [P.LABELS[i] for i in probs.argmax(axis=1)]
    frame = P.HOP / P.SR
    # chroma with the app's native code at 16 kHz
    y16 = P.resample(y, sr, 16000).astype(np.float32)
    os.makedirs("tmp", exist_ok=True)
    y16.tofile("tmp/in16.f32")
    subprocess.run([os.path.abspath("cpptest/test_chroma.exe"), "tmp/in16.f32", "tmp/chroma.csv"], check=True)
    rows = np.loadtxt("tmp/chroma.csv", delimiter=",")
    loud = np.percentile(rows[:, 13], 90)
    right = wrong2 = wrong5 = n = 0
    for r in rows:
        t, c, rms = r[0] - 0.128, r[1:13], r[13]       # centre of the 256 ms window
        i = int(t / frame)
        if i < 0 or i >= len(labels) or rms < 0.2 * loud: continue
        lab = labels[i]
        if lab in ("N", "X"): continue
        note, q = lab.split(":")
        root = P.NOTES.index(note)
        n += 1
        right += matches(c, root, q)
        wrong2 += matches(c, (root + 2) % 12, q)
        wrong5 += matches(c, (root + 5) % 12, q)
    print(f"{name:12s} {n:5d} readings: right chord accepted {100 * right / n:5.1f}%,"
          f"  wrong chord (+2) accepted {100 * wrong2 / n:5.1f}%,  (+5) {100 * wrong5 / n:5.1f}%")

if __name__ == "__main__":
    for s in (sys.argv[1:] or ["uska", "naina_morey", "kaa_karoon"]):
        run(s)


def segments(name):
    """Per chord segment: average the loud readings, then decide (what the app reports per chord)."""
    w = wave.open(f"audio/{name}.wav")
    y = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    sr = w.getframerate()
    y22 = y if sr == P.SR else P.resample(y, sr)
    labels = [P.LABELS[i] for i in P.Chords().probs(P.CQT()(y22)).argmax(axis=1)]
    frame = P.HOP / P.SR
    y16 = P.resample(y, sr, 16000).astype(np.float32)
    y16.tofile("tmp/in16.f32")
    subprocess.run([os.path.abspath("cpptest/test_chroma.exe"), "tmp/in16.f32", "tmp/chroma.csv"], check=True)
    rows = np.loadtxt("tmp/chroma.csv", delimiter=",")
    loud = np.percentile(rows[:, 13], 90)
    segs = []
    i = 0
    while i < len(labels):
        j = i
        while j < len(labels) and labels[j] == labels[i]: j += 1
        if labels[i] not in ("N", "X") and (j - i) * frame >= 0.5: segs.append((i * frame, j * frame, labels[i]))
        i = j
    stats = {k: [0, 0] for k in ("right", "+2", "+5", "+7", "rel")}
    for a, b, lab in segs:
        sel = rows[(rows[:, 0] - 0.128 >= a + 0.15) & (rows[:, 0] - 0.128 < b) & (rows[:, 13] >= 0.2 * loud)]
        if len(sel) < 2: continue
        c = sel[:, 1:13].mean(axis=0)
        note, q = lab.split(":"); root = P.NOTES.index(note)
        for k, sh in (("right", 0), ("+2", 2), ("+5", 5), ("+7", 7)):
            stats[k][0] += matches(c, (root + sh) % 12, q); stats[k][1] += 1
        # relative: the right chord beats the 3 most likely wrong guesses (+5, +7, relative minor/major)
        rs = score(c, template(root, q))
        alt = [score(c, template((root + s) % 12, q)) for s in (5, 7)] + [score(c, template((root + (9 if q not in MINORISH else 3)) % 12, "min" if q not in MINORISH else "maj"))]
        stats["rel"][0] += rs > max(alt); stats["rel"][1] += 1
    print(f"{name:12s} {stats['right'][1]:4d} chords: right accepted {100*stats['right'][0]/stats['right'][1]:5.1f}%  "
          + "  ".join(f"{k} {100*v[0]/v[1]:4.1f}%" for k, v in stats.items() if k != "right"))


def strum(midis, sr=16000, dur=0.5, rng=np.random.default_rng(1)):
    """Karplus-Strong strum: each string plucked 15 ms after the last, decaying like a steel string."""
    out = np.zeros(int(sr * dur))
    for k, m in enumerate(midis):
        f = 440 * 2 ** ((m - 69) / 12)
        n = int(sr / f)
        buf = rng.uniform(-1, 1, n)
        start = int(k * 0.015 * sr)
        y = np.zeros(len(out) - start)
        for i in range(len(y)):
            y[i] = buf[i % n]
            buf[i % n] = 0.996 * 0.5 * (buf[i % n] + buf[(i + 1) % n])
        out[start:] += y * 0.2
    return out

def voicing(root, minor):
    bass = 40 + (root - 4) % 12          # lowest root from E2 up
    third = 3 if minor else 4
    return [bass, bass + 7, bass + 12, bass + 12 + third, bass + 19, bass + 24]

def guitar(name, shift=0, noise=0.01):
    """The song's chords strummed on guitar (every half second), shifted by [shift] to play wrong ones."""
    w = wave.open(f"audio/{name}.wav")
    y = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    sr = w.getframerate()
    labels = [P.LABELS[i] for i in P.Chords().probs(P.CQT()(y if sr == P.SR else P.resample(y, sr))).argmax(axis=1)]
    frame = P.HOP / P.SR
    segs, i = [], 0
    while i < len(labels):
        j = i
        while j < len(labels) and labels[j] == labels[i]: j += 1
        if labels[i] not in ("N", "X") and (j - i) * frame >= 1.0: segs.append((i * frame, j * frame, labels[i]))
        i = j
    segs = segs[:80]
    total = int(segs[-1][1] * 16000) + 16000
    audio = np.zeros(total)
    cache = {}
    for a, b, lab in segs:
        note, q = lab.split(":"); root = (P.NOTES.index(note) + shift) % 12; minor = q in MINORISH
        key = (root, minor)
        if key not in cache: cache[key] = strum(voicing(root, minor))
        t = a
        while t + 0.5 <= b:
            s = int(t * 16000); audio[s:s + 8000] += cache[key]; t += 0.5
    audio += np.random.default_rng(2).normal(0, noise, len(audio))
    audio.astype(np.float32).tofile("tmp/in16.f32")
    subprocess.run([os.path.abspath("cpptest/test_chroma.exe"), "tmp/in16.f32", "tmp/chroma.csv"], check=True)
    rows = np.loadtxt("tmp/chroma.csv", delimiter=",")
    loud = np.percentile(rows[:, 13], 90)
    ok = frames_ok = frames = 0
    for a, b, lab in segs:
        note, q = lab.split(":"); root = P.NOTES.index(note)      # what the song expects
        sel = rows[(rows[:, 0] - 0.128 >= a + 0.15) & (rows[:, 0] - 0.128 < b) & (rows[:, 13] >= 0.2 * loud)]
        if len(sel) < 2: continue
        ok += matches(sel[:, 1:13].mean(axis=0), root, q)
        frames_ok += sum(matches(r[1:13], root, q) for r in sel); frames += len(sel)
    print(f"{name:12s} guitar {'right chords' if shift == 0 else f'chords {shift:+d} (wrong)'}: "
          f"{100 * ok / len(segs):5.1f}% of chords accepted, {100 * frames_ok / frames:5.1f}% of readings")


def notes_of(root, q):
    return {(root + i) % 12 for i in QUAL[q]}

def matches_strict(c, root, q):
    (br, bq), _ = best(c)
    if (br, bq) == (root, "min" if q in MINORISH else "maj"):
        return True
    return notes_of(br, bq) <= notes_of(root, q)

matches_loose = matches


def strum_additive(midis, sr=16000, dur=0.5, rng=np.random.default_rng(1)):
    """In-tune plucked strings: harmonics |sin(0.2*pi*h)|/h (plucked a fifth along), upper ones fade faster."""
    out = np.zeros(int(sr * dur))
    for k, m in enumerate(midis):
        f = 440 * 2 ** ((m - 69) / 12)
        start = int(k * 0.015 * sr)
        t = np.arange(len(out) - start) / sr
        y = np.zeros(len(t))
        for h in range(1, 11):
            if h * f > sr / 2: break
            y += abs(np.sin(0.2 * np.pi * h)) / h * np.exp(-t * (2 + 1.5 * h)) * np.sin(2 * np.pi * h * f * t + rng.uniform(0, 6.28))
        out[start:] += 0.15 * y
    return out

strum_ks = strum
strum = strum_additive
