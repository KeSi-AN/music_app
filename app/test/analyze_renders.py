"""Measures what Riyaz's engine actually did to a clip.

Timing only (for pitch use chroma_check.py: a whole-spectrum comparison is fooled by timbre). Timing: cross-correlates loudness envelopes after undoing the tempo.
Usage: python analyze_renders.py <render dir>
"""
import sys, wave
import numpy as np

def load(p):
    with wave.open(p) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).reshape(-1, w.getnchannels())
        return x.mean(axis=1).astype(np.float64) / 32768.0, w.getframerate()

def logspec(x, sr, cents=10, fmin=80.0, fmax=5000.0):
    n = 8192
    win = np.hanning(n)
    frames = [x[i:i + n] * win for i in range(0, len(x) - n, n // 2)]
    mag = np.mean([np.abs(np.fft.rfft(f)) for f in frames], axis=0)
    freqs = np.fft.rfftfreq(n, 1 / sr)
    nb = int(1200 / cents * np.log2(fmax / fmin))
    edges = fmin * 2 ** (np.arange(nb + 1) * cents / 1200)
    idx = np.digitize(freqs, edges) - 1
    out = np.zeros(nb)
    for b in range(nb):
        m = idx == b
        if m.any():
            out[b] = mag[m].mean()
    out = np.log(out + 1e-9)
    return out - out.mean()

def pitch_shift_semitones(ref, test, sr, cents=10, maxst=6):
    a, b = logspec(ref, sr, cents), logspec(test, sr, cents)
    best, score = 0, -1e9
    for k in range(-maxst * 100 // cents, maxst * 100 // cents + 1):
        if k >= 0:
            s = np.dot(a[:len(a) - k], b[k:]) / (len(a) - k)
        else:
            s = np.dot(a[-k:], b[:len(b) + k]) / (len(a) + k)
        if s > score:
            best, score = k, s
    return best * cents / 100

def envelope(x, sr, hop=441):
    e = np.array([np.sqrt(np.mean(x[i:i + hop] ** 2)) for i in range(0, len(x) - hop, hop)])
    return (e - e.mean()) / (e.std() + 1e-9), sr / hop

def lag_seconds(ref, test, sr, tempo=1.0):
    # stretch the test envelope back to the original time-base, then find the offset
    e1, rate = envelope(ref, sr)
    e2, _ = envelope(test, sr)
    t2 = np.arange(len(e2)) * tempo
    e2r = np.interp(np.arange(0, t2[-1]), t2, e2)
    n = min(len(e1), len(e2r))
    best, score = 0, -1e9
    for lag in range(-60, 61):
        a = e1[max(0, lag):n + min(0, lag)]
        b = e2r[max(0, -lag):n - max(0, lag)]
        m = min(len(a), len(b))
        s = np.dot(a[:m], b[:m]) / m
        if s > score:
            best, score = lag, s
    return best / rate, score

d = sys.argv[1]
src, sr = load(f"{d}/source_60s.wav")
orig, _ = load(f"{d}/orig.wav")
print("orig vs source: "
      "lag %.3f s (corr %.2f)" % lag_seconds(src, orig, sr))
for name, expect in [("k-3", -3), ("k+2", 2), ("k-3nat", -3)]:
    x, _ = load(f"{d}/{name}.wav")
    lag, c = lag_seconds(src, x, sr)
    print(f"{name}: "
          f"lag {lag:.3f} s (corr {c:.2f}), peak {np.abs(x).max():.2f}")
t, _ = load(f"{d}/t75.wav")
lag, c = lag_seconds(src, t, sr, tempo=0.75)
print(f"t75: "
      f"timing match at 0.75x corr {c:.2f} (vs 1.0x corr {lag_seconds(src, t, sr)[1]:.2f}), lag {lag:.3f} s")
