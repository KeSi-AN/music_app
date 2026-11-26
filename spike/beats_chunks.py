"""Does beat_this small run fast enough with shorter chunks, and stay accurate?"""
import time, numpy as np, librosa, torch, onnxruntime as ort
import run_spike as rs
from beat_this.preprocessing import LogMelSpect
from beat_this.inference import split_piece, aggregate_prediction, Postprocessor, load_model

bw_model = load_model("small0", "cpu").eval()
class BeatLogits(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, spect):
        out = self.m(spect); return out["beat"], out["downbeat"]
torch.onnx.export(BeatLogits(bw_model).eval(), (torch.randn(1, 500, 128).abs(),), "onnx/beat_this_small_dyn.onnx",
                  input_names=["mel"], output_names=["beat", "downbeat"], opset_version=17, dynamo=False,
                  dynamic_axes={"mel": {1: "t"}, "beat": {1: "t"}, "downbeat": {1: "t"}})
mel, post = LogMelSpect(), Postprocessor(type="minimal")
so = ort.SessionOptions(); so.intra_op_num_threads = 4
s = ort.InferenceSession("onnx/beat_this_small_dyn.onnx", so, providers=["CPUExecutionProvider"])
for song in rs.SONGS:
    y, _ = librosa.load(rs.HERE / "audio" / f"{song}.wav", sr=rs.SR, mono=True)
    ref = rs.load_ref(song); feat = rs.btc_features(y); pb, lb = rs.run_btc(feat, large=True)
    spect = mel(torch.tensor(y, dtype=torch.float32))
    res = []
    for chunk in (1500, 750, 400):
        chunks, starts = split_piece(spect, chunk, border_size=6, avoid_short_end=True)
        t0 = time.time()
        preds = [dict(zip(("beat", "downbeat"), map(lambda a: torch.tensor(a[0]), s.run(None, {"mel": c.unsqueeze(0).numpy()})))) for c in chunks]
        dt = time.time() - t0
        b, d = aggregate_prediction(preds, starts, spect.shape[0], chunk, 6, "keep_first", "cpu")
        beats, downbeats = post(b, d)
        beats = np.asarray(beats)
        sc = rs.score(ref, rs.btc_to_beats(pb, lb, beats))
        res.append(f"chunk {chunk}: {dt:4.1f}s {60/np.median(np.diff(beats)):5.1f}BPM chords {sc['root']*100:4.1f}% downbeats {len(downbeats)}")
    print(song, " | ".join(res))
