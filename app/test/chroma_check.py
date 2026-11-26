"""Key-shift check by note content: 10-cent pitch-class profile (envelope removed), circular xcorr.
Also reports peak level and how many samples hit full scale."""
import sys, wave
import numpy as np
sys.path.insert(0, __file__.rsplit("/", 1)[0])

def load(p):
    with wave.open(p) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).reshape(-1, w.getnchannels())
    return x.astype(np.float64) / 32768.0, w.getframerate()

def pcp(x, sr, fmin=110.0, fmax=2000.0, bins_per_oct=120):
    mono = x.mean(axis=1)
    n = 16384
    win = np.hanning(n)
    spec = np.mean([np.abs(np.fft.rfft(mono[i:i + n] * win)) for i in range(0, len(mono) - n, n // 2)], axis=0)
    f = np.fft.rfftfreq(n, 1 / sr)
    m = (f >= fmin) & (f <= fmax)
    f, s = f[m], np.log(spec[m] + 1e-9)
    # whiten: subtract a moving average over ~1/3 octave so only peaks (notes) remain
    cents = 1200 * np.log2(f / fmin)
    out = np.zeros(bins_per_oct)
    for i in range(len(f)):
        lo = np.searchsorted(cents, cents[i] - 200)
        hi = np.searchsorted(cents, cents[i] + 200)
        v = s[i] - s[lo:hi].mean()
        if v > 0:
            out[int(round(cents[i] / (1200 / bins_per_oct))) % bins_per_oct] += v
    return out / (np.linalg.norm(out) + 1e-9)

def shift(a, b):
    sc = [np.dot(a, np.roll(b, -k)) for k in range(len(a))]
    k = int(np.argmax(sc))
    st = k * 12 / len(a)
    return (st + 6) % 12 - 6, max(sc)

d = sys.argv[1]
src, sr = load(f"{d}/source_60s.wav")
ref = pcp(src, sr)
for name in sys.argv[2:]:
    x, _ = load(f"{d}/{name}.wav")
    st, c = shift(ref, pcp(x, sr))
    clip = int((np.abs(x) >= 0.999).sum())
    print(f"{name:8s} shift {st:+.1f} st (match {c:.2f})  peak {np.abs(x).max():.3f}  clipped samples {clip}")
x, _ = load(f"{d}/source_60s.wav"); print(f"{'source':8s} peak {np.abs(x).max():.3f} clipped {(np.abs(x)>=0.999).sum()}")
