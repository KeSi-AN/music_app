"""Which chroma recipe tells guitar chords apart best? numpy versions of candidates for chroma.cpp."""
import numpy as np, wave
import chroma_check as C
P = C.P
SR, N, HOP = 16000, 4096, 1600
freqs = np.arange(N // 2) * SR / N
win = np.hanning(N)

def spectrum(x):
    return np.abs(np.fft.rfft(x * win))[: N // 2]

def fold(vals, lo=65, hi=2100):
    c = np.zeros(12)
    for k in range(1, N // 2):
        f = freqs[k]
        if f < lo or f > hi: continue
        m = 69 + 12 * np.log2(f / 440); n = round(m)
        c[n % 12] += max(0, 1 - 2 * abs(m - n)) * vals[k]
    return c

# pitch salience: for each semitone p (MIDI 36..84), sum of magnitudes at its first harmonics
def salience_chroma(mag, H=6, decay=0.8):
    c = np.zeros(12)
    for p in range(36, 85):
        f0 = 440 * 2 ** ((p - 69) / 12)
        s = 0.0
        for h in range(1, H + 1):
            k = int(round(h * f0 * N / SR))
            if k >= N // 2: break
            s += decay ** (h - 1) * mag[max(0, k - 1):k + 2].max()
        c[p % 12] += s
    return c

VARIANTS = {
    "log":      lambda m: fold(np.log1p(100 * m)),
    "linear":   lambda m: fold(m),
    "sqrt":     lambda m: fold(np.sqrt(m)),
    "salience": lambda m: salience_chroma(m),
    "sal_sqrt": lambda m: salience_chroma(np.sqrt(m)),
}

def norm(c):
    c = c - c.min()
    return c / (c.max() + 1e-9)

def single_chords():
    out = {}
    for name, fn in VARIANTS.items():
        good = 0
        for root in range(12):
            for minor in (False, True):
                a = np.concatenate([C.strum(C.voicing(root, minor)) for _ in range(3)])
                cs = [norm(fn(spectrum(a[s:s + N]))) for s in range(0, len(a) - N, HOP)]
                (br, bq), _ = C.best(np.mean(cs, axis=0))
                good += (br, bq) == (root, "min" if minor else "maj")
        out[name] = good
    return out

if __name__ == "__main__":
    print("of 24 strummed major/minor chords, detected right:", single_chords())


def voicings(root, minor):
    t = 3 if minor else 4
    e = 40 + (root - 4) % 12                    # E-shape barre: R 5 R 3 5 R
    a = 45 + (root - 9) % 12                    # A-shape barre: R 5 R 3 5
    return [[e, e + 7, e + 12, e + 12 + t, e + 19, e + 24], [a, a + 7, a + 12, a + 12 + t, a + 19]]

HARM = [0, 12, 19, 24, 28, 31]                  # semitones above the note of harmonics 1..6

def htemplate(root, minor, d):
    t = np.zeros(12)
    for i in ([0, 3, 7] if minor else [0, 4, 7]) + [0]:
        for h, s in enumerate(HARM):
            t[(root + i + s) % 12] += d ** h
    return t

def best_h(c, d):
    sc = [((r, m), C.score(c, htemplate(r, m, d))) for r in range(12) for m in (False, True)]
    return max(sc, key=lambda x: x[1])[0]

def search():
    chromas = {name: [] for name in VARIANTS}
    for root in range(12):
        for minor in (False, True):
            for v in voicings(root, minor):
                a = np.concatenate([C.strum(v) for _ in range(3)])
                specs = [spectrum(a[s:s + N]) for s in range(0, len(a) - N, HOP)]
                for name, fn in VARIANTS.items():
                    chromas[name].append(((root, minor), np.mean([norm(fn(m)) for m in specs], axis=0)))
    for name, items in chromas.items():
        res = {d: sum(best_h(c, d) == k for k, c in items) for d in (0.0, 0.2, 0.35, 0.5, 0.65, 0.8)}
        print(f"{name:9s} correct of {len(items)} for harmonic weight d:", res)

if __name__ == "__main__":
    search()


def notes(r, minor):
    return {(r + i) % 12 for i in ([0, 3, 7] if minor else [0, 4, 7])}

def strict(c, root, minor, d):
    br, bm = best_h(c, d)
    return (br, bm) == (root, minor)

def evaluate(name_song, d=0.5, recipes=("log", "linear", "sqrt")):
    w = wave.open(f"audio/{name_song}.wav")
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
    y16 = P.resample(y, sr, 16000)
    # guitar playing the song's chords (right) and a fourth away (wrong)
    def guitar_audio(shift):
        audio = np.zeros(int(segs[-1][1] * SR) + SR); cache = {}
        for a, b, lab in segs:
            note, q = lab.split(":"); r = (P.NOTES.index(note) + shift) % 12; mi = q in C.MINORISH
            if (r, mi) not in cache: cache[(r, mi)] = C.strum(voicings(r, mi)[r % 2])
            t = a
            while t + 0.5 <= b:
                s = int(t * SR); audio[s:s + 8000] += cache[(r, mi)]; t += 0.5
        return audio + np.random.default_rng(2).normal(0, 0.01, len(audio))
    sources = {"mix": y16, "guitar": guitar_audio(0), "guitar+5": guitar_audio(5)}
    for src, audio in sources.items():
        mags = [(s + N) / SR for s in range(0, len(audio) - N, HOP)]
        specs = None
        line = []
        for rec in recipes:
            fn = VARIANTS[rec]
            ok = tot = 0
            rows = []
            for s in range(0, len(audio) - N, HOP):
                x = audio[s:s + N]
                rows.append(((s + N) / SR, norm(fn(spectrum(x))), np.sqrt(np.mean(x * x))))
            loud = np.percentile([r[2] for r in rows], 90)
            for a, b, lab in segs:
                sel = [c for t, c, rms in rows if t - N / SR >= a + 0.05 and t <= b + 0.02 and rms >= 0.2 * loud]
                if len(sel) < 2: continue
                note, q = lab.split(":"); root = P.NOTES.index(note)
                ok += strict(np.mean(sel, axis=0), root, q in C.MINORISH, d); tot += 1
            line.append(f"{rec} {100 * ok / max(tot, 1):5.1f}%")
        print(f"{name_song:12s} {src:9s} expected chord accepted: " + "   ".join(line))

if __name__ == "__main__":
    import sys
    for s in sys.argv[1:] or ["uska", "naina_morey"]:
        evaluate(s)
