"""MDX-Net vocal separation written the way the phone will run it (numpy STFT + ONNX Runtime).

Mirrors audio-separator's MDXSeparator: pad n_fft/2 each side, chunks of hop*(dim_t-1) samples,
STFT (hann periodic, centre, n_fft, hop 1024) cut to dim_f bins, first 3 bins zeroed, model,
iSTFT, Hann-weighted overlap-add, trim. Instrumental = mix - vocals * compensate.
"""
import time
import numpy as np
import onnxruntime as ort

HOP = 1024
DIM_T = 256

class Mdx:
    def __init__(self, path, n_fft, dim_f, compensate, threads=4):
        so = ort.SessionOptions(); so.intra_op_num_threads = threads
        self.s = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
        self.n_fft, self.dim_f, self.comp = n_fft, dim_f, compensate
        self.win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n_fft) / n_fft)   # periodic Hann
        self.chunk = HOP * (DIM_T - 1)
        self.trim = n_fft // 2

    def stft(self, x):                     # x: (2, chunk) -> (4, dim_f, DIM_T)
        n, h = self.n_fft, HOP
        xp = np.pad(x, ((0, 0), (n // 2, n // 2)), mode="reflect")    # torch.stft center=True, pad_mode reflect
        frames = 1 + (xp.shape[1] - n) // h
        out = np.zeros((4, self.dim_f, frames), dtype=np.float32)
        for c in range(2):
            for t in range(frames):
                X = np.fft.rfft(xp[c, t * h: t * h + n] * self.win)[: self.dim_f]
                out[2 * c, :, t] = X.real
                out[2 * c + 1, :, t] = X.imag
        out[:, :3, :] = 0
        return out

    def istft(self, spec, length):         # (4, dim_f, frames) -> (2, length)
        n, h = self.n_fft, HOP
        frames = spec.shape[2]
        full = np.zeros((2, n // 2 + 1, frames), dtype=np.complex128)
        full[0, : self.dim_f] = spec[0] + 1j * spec[1]
        full[1, : self.dim_f] = spec[2] + 1j * spec[3]
        total = n + h * (frames - 1)
        y = np.zeros((2, total)); wsum = np.zeros(total)
        for t in range(frames):
            seg = np.fft.irfft(full[:, :, t], n=n, axis=1) * self.win
            y[:, t * h: t * h + n] += seg
            wsum[t * h: t * h + n] += self.win ** 2
        y = y[:, n // 2: n // 2 + length] / np.maximum(wsum[n // 2: n // 2 + length], 1e-8)
        return y

    def vocals(self, mix, overlap=0.25, on_chunk=None):
        """mix: (2, samples) at 44.1 kHz. Returns the vocal estimate, same shape."""
        n = mix.shape[1]
        gen = self.chunk - 2 * self.trim
        pad = gen + self.trim - (n % gen)
        m = np.concatenate([np.zeros((2, self.trim)), mix, np.zeros((2, pad))], axis=1).astype(np.float32)
        step = int((1 - overlap) * self.chunk)
        result = np.zeros_like(m); divider = np.zeros_like(m)
        starts = list(range(0, m.shape[1], step))
        for k, i in enumerate(starts):
            end = min(i + self.chunk, m.shape[1]); size = end - i
            part = m[:, i:end]
            if size < self.chunk:
                part = np.concatenate([part, np.zeros((2, self.chunk - size), np.float32)], axis=1)
            spec = self.stft(part)
            pred = self.s.run(None, {"input": spec[None]})[0][0]
            wave = self.istft(pred, self.chunk)[:, :size]
            w = np.hanning(size) if overlap else np.ones(size)
            result[:, i:end] += wave * w; divider[:, i:end] += w
            if on_chunk: on_chunk(k + 1, len(starts))
        out = result / np.maximum(divider, 1e-8)
        return out[:, self.trim: self.trim + n]

    def separate(self, mix, overlap=0.25):
        v = self.vocals(mix, overlap)
        return v, mix - v * self.comp

if __name__ == "__main__":
    import soundfile as sf
    import sep_spike as S
    mdx = Mdx("C:/tmp/audio-separator-models/UVR_MDXNET_9482.onnx", n_fft=6144, dim_f=2048, compensate=1.035)
    data = S.clips()
    for ov in (0.25, 0.0):
        t0 = time.time(); sa, sv = [], []
        for p, voc, acc in data:
            x, _ = sf.read(p, dtype="float32")
            v, a = mdx.separate(x.T, overlap=ov)
            sa.append(S.sdr(acc, a.T)); sv.append(S.sdr(voc, v.T))
        print(f"portable MDX 9482 overlap {ov}: instrumental {np.median(sa):5.2f} dB, vocals {np.median(sv):5.2f} dB, "
              f"{(time.time()-t0)/(7*len(data)):.2f} s/s")


def vocals_trimmed(mdx, mix, on_chunk=None):
    """UVR's original scheme: chunks advance by gen = chunk - n_fft and only their middle part is
    kept, so there are no hard joins and no overlap-add. This is what the phone runs."""
    n = mix.shape[1]
    gen = mdx.chunk - 2 * mdx.trim
    n_chunks = (n + gen - 1) // gen
    m = np.concatenate([np.zeros((2, mdx.trim)), mix, np.zeros((2, n_chunks * gen - n + mdx.trim))], axis=1).astype(np.float32)
    out = np.zeros((2, n_chunks * gen), dtype=np.float32)
    for k in range(n_chunks):
        part = m[:, k * gen: k * gen + mdx.chunk]
        pred = mdx.s.run(None, {"input": mdx.stft(part)[None]})[0][0]
        wave = mdx.istft(pred, mdx.chunk)
        out[:, k * gen:(k + 1) * gen] = wave[:, mdx.trim: mdx.trim + gen]
        if on_chunk: on_chunk(k + 1, n_chunks)
    return out[:, :n]

if __name__ == "__main__":
    t0 = time.time(); sa, sv = [], []
    for p, voc, acc in data:
        x, _ = sf.read(p, dtype="float32")
        v = vocals_trimmed(mdx, x.T); a = x.T - v * mdx.comp
        sa.append(S.sdr(acc, a.T)); sv.append(S.sdr(voc, v.T))
    print(f"portable MDX 9482 trimmed chunks: instrumental {np.median(sa):5.2f} dB, vocals {np.median(sv):5.2f} dB, "
          f"{(time.time()-t0)/(7*len(data)):.2f} s/s")
