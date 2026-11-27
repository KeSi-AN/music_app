"""Phase 3 spike: which vocal remover is good enough AND fast enough for the phone?

Data: MUSDB18 7-second test clips (true vocals + accompaniment known).
Score: SDR in dB (higher is better) of the instrumental (what karaoke plays) and of the vocals.
Speed: seconds of compute per second of audio on this laptop's CPU.
Usage: .venv/Scripts/python sep_spike.py [n_clips]
"""
import os, sys, time, glob, shutil
from pathlib import Path
import numpy as np
import soundfile as sf
import musdb

HERE = Path(__file__).parent
OUT = HERE / "sep"
N = int(sys.argv[1]) if len(sys.argv) > 1 else 10

def sdr(ref, est):
    n = min(len(ref), len(est))
    ref, est = ref[:n], est[:n]
    return 10 * np.log10((ref ** 2).sum() / (((ref - est) ** 2).sum() + 1e-9) + 1e-9)

def clips():
    db = musdb.DB(root=os.path.expanduser("~/MUSDB18/MUSDB18-7"), subsets="test")
    (OUT / "mix").mkdir(parents=True, exist_ok=True)
    out = []
    for t in db[:N]:
        p = OUT / "mix" / f"{t.name[:40].replace(' ', '_')}.wav"
        if not p.exists():
            sf.write(p, t.audio, t.rate)
        out.append((p, t.targets["vocals"].audio, t.targets["accompaniment"].audio))
    return out

def run_demucs(path, model):
    import torch
    from demucs.pretrained import get_model
    from demucs.apply import apply_model
    if model not in run_demucs.cache:
        m = get_model(model); m.eval(); run_demucs.cache[model] = m
    m = run_demucs.cache[model]
    x, sr = sf.read(path, dtype="float32")
    wav = torch.tensor(x.T)[None]
    ref = wav.mean(1, keepdim=True)
    with torch.no_grad():
        out = apply_model(m, (wav - ref.mean()) / ref.std(), shifts=0, split=True, overlap=0.25, progress=False)[0]
    out = out * ref.std() + ref.mean()
    srcs = dict(zip(m.sources, out.numpy()))
    voc = srcs["vocals"].T
    acc = sum(v for k, v in srcs.items() if k != "vocals").T
    return voc, acc
run_demucs.cache = {}

def run_uvr(path, model_file):
    from audio_separator.separator import Separator
    if model_file not in run_uvr.cache:
        sep = Separator(output_dir=str(OUT / "tmp"), log_level=40)
        sep.load_model(model_filename=model_file)
        run_uvr.cache[model_file] = sep
    sep = run_uvr.cache[model_file]
    shutil.rmtree(OUT / "tmp", ignore_errors=True); (OUT / "tmp").mkdir()
    files = sep.separate(str(path))
    voc = acc = None
    for f in files:
        f = f if os.path.isabs(f) else str(OUT / "tmp" / f)
        a, _ = sf.read(f, dtype="float32")
        if "(Vocals)" in f: voc = a
        else: acc = a
    return voc, acc
run_uvr.cache = {}

CANDIDATES = [
    ("htdemucs (4 stems)", lambda p: run_demucs(p, "htdemucs")),
    ("MDX Kim_Vocal_2", lambda p: run_uvr(p, "Kim_Vocal_2.onnx")),
    ("MDX Voc_FT", lambda p: run_uvr(p, "UVR-MDX-NET-Voc_FT.onnx")),
    ("MDX Inst_HQ_3", lambda p: run_uvr(p, "UVR-MDX-NET-Inst_HQ_3.onnx")),
    ("MDX 9482 (small)", lambda p: run_uvr(p, "UVR_MDXNET_9482.onnx")),
]

def main():
    data = clips()
    only = os.environ.get("ONLY")
    for name, fn in CANDIDATES:
        if only and only not in name:
            continue
        try:
            fn(data[0][0])                       # warm-up / model download
        except Exception as e:
            print(f"{name}: failed to load ({type(e).__name__}: {e})"); continue
        t0 = time.time(); sv, sa = [], []
        for p, voc, acc in data:
            ev, ea = fn(p)
            sv.append(sdr(voc, ev)); sa.append(sdr(acc, ea))
        dt = time.time() - t0
        print(f"{name:20s} instrumental SDR {np.median(sa):5.2f} dB   vocals SDR {np.median(sv):5.2f} dB   "
              f"{dt / (7.0 * len(data)):.2f} s per audio second")

if __name__ == "__main__":
    main()
