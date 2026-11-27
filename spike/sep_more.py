"""Lighter separators: Open-Unmix (umxhq, umxl) and int8-quantised MDX 9482."""
import time, numpy as np, torch, soundfile as sf, onnxruntime as ort
import sep_spike as S
from onnxruntime.quantization import quantize_dynamic, QuantType

data = S.clips()

def umx(name):
    from openunmix import predict
    def run(p):
        x, sr = sf.read(p, dtype="float32")
        est = predict.separate(torch.tensor(x.T), rate=sr, model_str_or_path=name, targets=["vocals"], residual=True, device="cpu")
        voc = est["vocals"][0].numpy().T
        acc = est["residual"][0].numpy().T
        return voc, acc
    return run

for name in ("umxhq", "umxl"):
    fn = umx(name); fn(data[0][0])
    torch.set_num_threads(4)
    t0 = time.time(); sv, sa = [], []
    for p, v, a in data:
        ev, ea = fn(p); sv.append(S.sdr(v, ev)); sa.append(S.sdr(a, ea))
    print(f"{name:20s} instrumental SDR {np.median(sa):5.2f} dB   vocals SDR {np.median(sv):5.2f} dB   {(time.time()-t0)/(7*len(data)):.2f} s/s (torch, 4 threads)")

src = "C:/tmp/audio-separator-models/UVR_MDXNET_9482.onnx"
dst = "C:/tmp/audio-separator-models/UVR_MDXNET_9482_int8.onnx"
quantize_dynamic(src, dst, weight_type=QuantType.QUInt8, op_types_to_quantize=["Conv", "ConvTranspose", "MatMul", "Gemm"])
x = np.random.randn(1, 4, 2048, 256).astype(np.float32) * 0.1
for f in (src, dst):
    so = ort.SessionOptions(); so.intra_op_num_threads = 4
    s = ort.InferenceSession(f, so, providers=["CPUExecutionProvider"]); s.run(None, {"input": x})
    t0 = time.time(); y = s.run(None, {"input": x})[0]; dt = time.time() - t0
    print(f"{f.split('/')[-1]:32s} {dt:.2f} s/chunk")
