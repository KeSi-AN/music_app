"""Export BTC (large vocabulary) and beat_this small0 to ONNX and check they match PyTorch."""
import time, numpy as np, torch, yaml, onnxruntime as ort
import run_spike as rs
from btc_model import BTC_model
from utils.hparams import HParams
from beat_this.inference import load_model

torch.set_grad_enabled(False)

# ---- BTC
config = HParams(**yaml.safe_load(open(rs.HERE / "ext/btc/run_config.yaml")))
config.feature["large_voca"] = True
config.model["num_chords"] = 170
cp = torch.load(rs.HERE / "ext/btc/test/btc_model_large_voca.pt", map_location="cpu", weights_only=False)
btc = BTC_model(config=config.model); btc.load_state_dict(cp["model"]); btc.eval()

class BtcLogits(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, x):                       # x: (1, 108, 144), already (feature - mean) / std
        h, _ = self.m.self_attn_layers(x)
        return self.m.output_layer.output_projection(h)   # (1, 108, 170)

wrap = BtcLogits(btc).eval()
x = torch.randn(1, 108, 144)
torch.onnx.export(wrap, (x,), "onnx/btc_large.onnx", input_names=["cqt"], output_names=["logits"], opset_version=17, dynamo=False)
np.save("onnx/btc_mean_std.npy", np.stack([np.asarray(cp["mean"]).reshape(-1) * np.ones(144), np.asarray(cp["std"]).reshape(-1) * np.ones(144)]))
s = ort.InferenceSession("onnx/btc_large.onnx", providers=["CPUExecutionProvider"])
a = wrap(x).numpy(); b = s.run(None, {"cqt": x.numpy()})[0]
print("BTC max abs diff:", float(np.abs(a - b).max()), " argmax equal:", bool((a.argmax(-1) == b.argmax(-1)).all()))
print("BTC mean/std shape", np.shape(cp["mean"]), np.shape(cp["std"]))

# ---- beat_this small0
bt = load_model("small0", "cpu").eval()
class BeatLogits(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, spect):                   # (1, 1500, 128)
        out = self.m(spect)
        return out["beat"], out["downbeat"]
bw = BeatLogits(bt).eval()
sp = torch.randn(1, 1500, 128).abs()
torch.onnx.export(bw, (sp,), "onnx/beat_this_small.onnx", input_names=["mel"], output_names=["beat", "downbeat"], opset_version=17, dynamo=False)
s2 = ort.InferenceSession("onnx/beat_this_small.onnx", providers=["CPUExecutionProvider"])
a1, a2 = bw(sp); b1, b2 = s2.run(None, {"mel": sp.numpy()})
print("beat_this max abs diff:", float(np.abs(a1.numpy() - b1).max()), float(np.abs(a2.numpy() - b2).max()))

# ---- timing for a 5-minute song with ONNX Runtime, 1 thread (phone-like pessimism)
so = ort.SessionOptions(); so.intra_op_num_threads = 1
s1t = ort.InferenceSession("onnx/btc_large.onnx", so, providers=["CPUExecutionProvider"])
s2t = ort.InferenceSession("onnx/beat_this_small.onnx", so, providers=["CPUExecutionProvider"])
t0 = time.time()
for _ in range(int(300 / (108 * 2048 / 22050)) + 1): s1t.run(None, {"cqt": x.numpy()})
t1 = time.time()
for _ in range(int(300 * 50 / 1488) + 1): s2t.run(None, {"mel": sp.numpy()})
t2 = time.time()
print(f"5-min song, 1 thread: BTC {t1-t0:.2f} s, beat_this small {t2-t1:.2f} s")
import os
print({f: f"{os.path.getsize('onnx/'+f)/1e6:.1f} MB" for f in os.listdir("onnx")})
