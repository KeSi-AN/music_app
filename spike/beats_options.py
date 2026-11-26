"""Cheaper beat options: ORT threads, int8 beat_this, classic DP tracker. Scored by chord agreement + tempo."""
import time, numpy as np, librosa, torch, onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
import run_spike as rs
from beat_this.preprocessing import LogMelSpect
from beat_this.inference import split_piece, aggregate_prediction
from beat_this.inference import Postprocessor

quantize_dynamic("onnx/beat_this_small.onnx", "onnx/beat_this_small_int8.onnx", weight_type=QuantType.QInt8)
mel = LogMelSpect()
post = Postprocessor(type="minimal")

def ort_beats(path, threads, y):
    so = ort.SessionOptions(); so.intra_op_num_threads = threads
    s = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    spect = mel(torch.tensor(y, dtype=torch.float32))
    chunks, starts = split_piece(spect, 1500, border_size=6, avoid_short_end=True)
    t0 = time.time()
    preds = []
    for c in chunks:
        b, d = s.run(None, {"mel": c.unsqueeze(0).numpy()})
        preds.append({"beat": torch.tensor(b[0]), "downbeat": torch.tensor(d[0])})
    dt = time.time() - t0
    pb, pd = aggregate_prediction(preds, starts, spect.shape[0], 1500, 6, "keep_first", "cpu")
    beats, downbeats = post(pb, pd)
    return np.asarray(beats), np.asarray(downbeats), dt

for song in rs.SONGS:
    y, _ = librosa.load(rs.HERE / "audio" / f"{song}.wav", sr=rs.SR, mono=True)
    ref = rs.load_ref(song)
    feat = rs.btc_features(y)
    pb, lb = rs.run_btc(feat, large=True)
    out = []
    for label, path, th in (("fp32 x1", "onnx/beat_this_small.onnx", 1), ("fp32 x4", "onnx/beat_this_small.onnx", 4),
                            ("int8 x4", "onnx/beat_this_small_int8.onnx", 4)):
        beats, db, dt = ort_beats(path, th, y)
        sc = rs.score(ref, rs.btc_to_beats(pb, lb, beats))
        out.append(f"{label}: {dt:4.1f}s {60/np.median(np.diff(beats)):5.1f}BPM chords {sc['root']*100:4.1f}%")
    t0 = time.time(); tempo, bf = librosa.beat.beat_track(y=y, sr=rs.SR, hop_length=512); dt = time.time() - t0
    beats = librosa.frames_to_time(bf, sr=rs.SR, hop_length=512)
    sc = rs.score(ref, rs.btc_to_beats(pb, lb, beats))
    out.append(f"classic DP: {dt:4.1f}s {60/np.median(np.diff(beats)):5.1f}BPM chords {sc['root']*100:4.1f}%")
    print(song, " | ".join(out))
