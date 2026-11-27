import sys, subprocess, numpy as np, soundfile as sf
sys.path.insert(0, "..")
import mdx_portable as M
import sep_spike as S

p, voc, acc = S.clips()[1]
x, sr = sf.read(p, dtype="float32")
pcm = np.clip(np.round(x * 32768), -32768, 32767).astype(np.int16)
pcm.tofile("song.pcm")
mix = (pcm.astype(np.float32) / 32768.0).T                    # what C++ sees

mdx = M.Mdx("C:/tmp/audio-separator-models/UVR_MDXNET_9482.onnx", n_fft=6144, dim_f=2048, compensate=1.035)
out = subprocess.run(["./test_sep.exe", "song.pcm", "44100"], capture_output=True, text=True).stdout
print("C++:", out.strip().replace("\n", " | "))
gen = mdx.chunk - 2 * mdx.trim
n = mix.shape[1]; n_chunks = (n + gen - 1) // gen
m = np.concatenate([np.zeros((2, mdx.trim)), mix, np.zeros((2, n_chunks * gen - n + mdx.trim))], axis=1).astype(np.float32)
for k in range(2):
    ref = mdx.stft(m[:, k * gen: k * gen + mdx.chunk])
    got = np.fromfile(f"in_{k}.f32", dtype=np.float32).reshape(ref.shape)
    print(f"chunk {k} input: max |diff| {np.abs(ref-got).max():.2e} (max |ref| {np.abs(ref).max():.2f})")
preds = []
for k in range(n_chunks):
    pred = mdx.s.run(None, {"input": mdx.stft(m[:, k * gen: k * gen + mdx.chunk])[None]})[0][0]
    pred.astype(np.float32).tofile(f"pred_{k}.f32"); preds.append(pred)
subprocess.run(["./test_sep.exe", "song.pcm", "44100", "x"], capture_output=True, text=True)
v_ref = M.vocals_trimmed(mdx, mix)
v_got = np.fromfile("vocals.f32", dtype=np.float32).reshape(-1, 2).T[:, :n]
print(f"vocals: max |diff| {np.abs(v_ref - v_got).max():.2e}, SDR vs truth C++ {S.sdr(voc, v_got.T):.2f} dB / numpy {S.sdr(voc, v_ref.T):.2f} dB")
b = np.fromfile("backing.pcm", dtype=np.int16).reshape(-1, 2).astype(np.float32) / 32768
print(f"backing: {len(b)} frames (song {n}); instrumental SDR {S.sdr(acc, b):.2f} dB")
