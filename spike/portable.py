"""Phone-portable versions of every analysis step, in plain numpy, written the way the C++ will be.

Nothing here calls librosa at analysis time. librosa is only used (in check()) to compare against.
Steps:
  resample()     windowed-sinc resampler (any ratio) to 22050 Hz mono
  cqt()          frame-based constant-Q transform (Brown & Puckette sparse spectral kernels),
                 same bins as BTC expects: 144 bins from C1, 24 per octave, hop 2048
  onset_env()    log-spectral-flux onset strength (n_fft 2048, hop 512)
  tempo()        autocorrelation tempo with a log-normal prior around 120 BPM
  beat_track()   Ellis (2007) dynamic-programming beat tracker
Then BTC (ONNX) on our CQT, beat-synchronous chords, key from chords, bar lines from chord changes.
"""
import time
import numpy as np
import onnxruntime as ort

SR = 22050
HOP = 2048
NBINS, BPO = 144, 24
FMIN = 32.70319566257483     # C1

# ------------------------------------------------------------------ resampling

def resample(x, sr_in, sr_out=SR, half_width=16, rolloff=0.945):
    """Band-limited interpolation with a Kaiser-windowed sinc (what the C++ will do)."""
    if sr_in == sr_out:
        return x.astype(np.float32)
    ratio = sr_out / sr_in
    cutoff = min(1.0, ratio) * rolloff                 # in units of the input Nyquist
    n_out = int(len(x) * ratio)
    t = np.arange(n_out) / ratio                        # output sample positions in input samples
    base = np.floor(t).astype(np.int64)
    frac = t - base
    out = np.zeros(n_out, dtype=np.float64)
    beta = 8.6
    for k in range(-half_width + 1, half_width + 1):
        idx = base + k
        d = k - frac                                    # distance from output point to input sample
        w = np.i0(beta * np.sqrt(np.clip(1 - (d / half_width) ** 2, 0, 1))) / np.i0(beta)
        h = cutoff * np.sinc(cutoff * d) * w
        valid = (idx >= 0) & (idx < len(x))
        out[valid] += h[valid] * x[idx[valid]]
    return out.astype(np.float32)

# ------------------------------------------------------------------ CQT

class CQT:
    """Brown & Puckette: CQT[k, t] = sum_f X_t[f] * K_k[f], with K_k the (sparse) spectrum of a
    Hann-windowed complex exponential of length Q*sr/f_k, L1-normalised like librosa's filters."""

    def __init__(self, sr=SR, nbins=NBINS, bpo=BPO, fmin=FMIN, tuning=0.0, sparsity=0.01):
        self.freqs = fmin * 2.0 ** ((np.arange(nbins) + tuning) / bpo)
        Q = 1.0 / (2.0 ** (1.0 / bpo) - 1.0)
        self.lengths = Q * sr / self.freqs
        self.nfft = int(2 ** np.ceil(np.log2(self.lengths.max())))
        kernels = np.zeros((nbins, self.nfft), dtype=np.complex128)
        for k, (f, L) in enumerate(zip(self.freqs, self.lengths)):
            n = np.arange(-int(np.floor(L / 2)), int(np.ceil(L / 2)))   # centred on 0, librosa-style
            w = np.hanning(len(n) + 2)[1:-1]
            sig = w * np.exp(2j * np.pi * f * n / sr)
            sig /= np.abs(sig).sum()                                     # norm=1
            buf = np.zeros(self.nfft, dtype=np.complex128)
            start = self.nfft // 2 - len(n) // 2
            buf[start:start + len(n)] = sig
            kernels[k] = np.fft.fft(buf)
        # sparsify each kernel spectrum: drop the smallest coefficients holding `sparsity` of the L1 mass
        self.idx, self.val = [], []
        for k in range(nbins):
            mag = np.abs(kernels[k])
            order = np.argsort(mag)
            csum = np.cumsum(mag[order]) / mag.sum()
            keep = order[csum > sparsity]
            keep.sort()
            self.idx.append(keep)
            self.val.append(np.conj(kernels[k][keep]) / self.nfft)
        self.scale = np.sqrt(self.lengths)               # librosa scale=True

    def __call__(self, y, hop=HOP):
        n_frames = 1 + len(y) // hop
        pad = self.nfft // 2
        yp = np.pad(y, (pad, pad + hop))
        out = np.zeros((len(self.idx), n_frames), dtype=np.float32)
        win_cache = None
        for t in range(n_frames):
            X = np.fft.fft(yp[t * hop: t * hop + self.nfft])
            for k in range(len(self.idx)):
                out[k, t] = abs(np.dot(X[self.idx[k]], self.val[k])) * self.scale[k]
        return out

# ------------------------------------------------------------------ beats

def onset_env(y, sr=SR, n_fft=2048, hop=512):
    win = np.hanning(n_fft + 1)[:-1]
    n_frames = 1 + (len(y) - n_fft) // hop
    S = np.empty((n_frames, n_fft // 2 + 1), dtype=np.float32)
    for t in range(n_frames):
        S[t] = np.abs(np.fft.rfft(y[t * hop: t * hop + n_fft] * win))
    # keep 30 Hz .. 8 kHz, log-compress, positive spectral flux
    f = np.fft.rfftfreq(n_fft, 1 / sr)
    S = np.log1p(100 * S[:, (f >= 30) & (f <= 8000)])
    flux = np.maximum(0, S[1:] - S[:-1]).mean(axis=1)
    flux = np.concatenate([[0], flux])
    # remove slow trend, normalise
    k = int(round(sr / hop))                    # ~1 s moving average
    trend = np.convolve(flux, np.ones(k) / k, mode="same")
    env = np.maximum(0, flux - trend)
    return env / (env.std() + 1e-9), sr / hop

def tempo(env, fps, center_bpm=120.0, octave_std=1.0):
    max_lag = int(fps * 60 / 30)                 # down to 30 BPM
    ac = np.correlate(env, env, mode="full")[len(env) - 1: len(env) - 1 + max_lag + 1]
    lags = np.arange(1, len(ac))
    bpm = 60 * fps / lags
    prior = np.exp(-0.5 * (np.log2(bpm / center_bpm) / octave_std) ** 2)
    score = ac[1:] * prior
    score[bpm > 220] = 0
    best = int(lags[np.argmax(score)])
    return 60 * fps / best, best

def beat_track(env, fps, bpm, tightness=100.0):
    period = 60 * fps / bpm
    # local score: onset envelope smoothed with a Gaussian of width ~ period/32
    g = np.exp(-0.5 * (np.arange(-period, period + 1) * 32 / period) ** 2)
    local = np.convolve(env, g, mode="same")
    n = len(local)
    backlink = -np.ones(n, dtype=np.int64)
    cum = local.copy()
    lo, hi = int(round(-2 * period)), int(round(-period / 2))
    window = np.arange(lo, hi + 1)
    txwt = -tightness * (np.log(-window / period)) ** 2
    first = True
    for i in range(n):
        z = i + window
        valid = z >= 0
        if not valid.any():
            continue
        cand = txwt[valid] + cum[z[valid]]
        j = int(np.argmax(cand))
        cum[i] = local[i] + cand[j]
        backlink[i] = z[valid][j]
    # pick the best end point among local maxima near the end, then trace back
    maxes = np.where((cum[1:-1] > cum[:-2]) & (cum[1:-1] >= cum[2:]))[0] + 1
    tail = maxes[maxes > n - 2 * period] if len(maxes) else np.array([n - 1])
    i = int(tail[np.argmax(cum[tail])]) if len(tail) else n - 1
    beats = [i]
    while backlink[beats[-1]] >= 0:
        beats.append(int(backlink[beats[-1]]))
    beats = np.array(beats[::-1])
    # trim weak beats at the very start and end (silence)
    thresh = 0.5 * np.sqrt(np.mean(local[beats] ** 2))
    keep = np.where(local[beats] > thresh)[0]
    if len(keep):
        beats = beats[keep[0]: keep[-1] + 1]
    return beats / fps

# ------------------------------------------------------------------ chords

NOTES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
QUALS = ["min", "maj", "dim", "aug", "min6", "maj6", "min7", "minmaj7", "maj7", "7", "dim7", "hdim7", "sus2", "sus4"]
LABELS = [NOTES[i // 14] + ":" + QUALS[i % 14] for i in range(168)] + ["X", "N"]

class Chords:
    def __init__(self, onnx_path="onnx/btc_large.onnx", meanstd="onnx/btc_mean_std.npy", threads=1):
        so = ort.SessionOptions(); so.intra_op_num_threads = threads
        self.s = ort.InferenceSession(onnx_path, so, providers=["CPUExecutionProvider"])
        ms = np.load(meanstd)
        self.mean, self.std = float(ms[0][0]), float(ms[1][0])

    def probs(self, cqt_mag):
        x = (np.log(cqt_mag + 1e-6).T - self.mean) / self.std          # (frames, 144)
        T = 108
        pad = (-len(x)) % T
        x = np.pad(x, ((0, pad), (0, 0))).astype(np.float32)
        out = []
        for i in range(len(x) // T):
            logits = self.s.run(None, {"cqt": x[None, i * T:(i + 1) * T]})[0][0]
            e = np.exp(logits - logits.max(axis=1, keepdims=True))
            out.append(e / e.sum(axis=1, keepdims=True))
        return np.concatenate(out)[: cqt_mag.shape[1]]

def beat_sync_labels(probs, beat_times, frame_sec=HOP / SR):
    bf = np.clip(np.round(np.asarray(beat_times) / frame_sec).astype(int), 0, len(probs) - 1)
    out = []
    for a, b in zip(bf[:-1], bf[1:]):
        out.append(LABELS[int(probs[a:max(b, a + 1)].mean(axis=0).argmax())])
    return out

def bar_phase(labels, beats_per_bar=4):
    """Downbeat guess: the beat phase where chords change most often."""
    changes = np.zeros(beats_per_bar)
    for i in range(1, len(labels)):
        if labels[i] != labels[i - 1]:
            changes[i % beats_per_bar] += 1
    return int(np.argmax(changes))
