"""Vocal tracks for the Whisper spike: MDX 9482 (what the phone runs), written as 16 kHz mono WAV."""
import wave, sys, time
import numpy as np
import portable as P
import mdx_portable as M

mdx = M.Mdx("C:/tmp/audio-separator-models/UVR_MDXNET_9482.onnx", n_fft=6144, dim_f=2048, compensate=1.035)
for name in sys.argv[1:] or ["uska", "naina_morey", "kaa_karoon"]:
    w = wave.open(f"audio/{name}.wav"); sr = w.getframerate()
    y = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    y44 = P.resample(y, sr, 44100)
    t0 = time.time()
    voc = M.vocals_trimmed(mdx, np.stack([y44, y44]))
    v16 = P.resample(voc.mean(axis=0), 44100, 16000)
    v16 = (np.clip(v16 / max(1e-6, np.abs(v16).max()) * 0.9, -1, 1) * 32767).astype(np.int16)
    o = wave.open(f"tmp/{name}_vocals16.wav", "wb"); o.setnchannels(1); o.setsampwidth(2); o.setframerate(16000); o.writeframes(v16.tobytes()); o.close()
    print(f"{name}: vocals in {time.time() - t0:.0f} s")
