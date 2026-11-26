"""beat_this small0 vs final0: speed, tempo, and chord agreement when chords are beat-synced on small0."""
import time
import numpy as np, librosa
import run_spike as rs
from beat_this.inference import File2Beats

small = File2Beats(checkpoint_path="small0", device="cpu", dbn=False)
final = File2Beats(checkpoint_path="final0", device="cpu", dbn=False)
for song in rs.SONGS:
    wav = str(rs.HERE / "audio" / f"{song}.wav")
    t0 = time.time(); bs, ds = small(wav); ts = time.time() - t0
    t0 = time.time(); bf, df = final(wav); tf = time.time() - t0
    # beat agreement: fraction of final0 beats with a small0 beat within 70 ms
    hit = np.mean([np.min(np.abs(bs - b)) < 0.07 for b in bf])
    y, _ = librosa.load(wav, sr=rs.SR, mono=True)
    feat = rs.btc_features(y)
    pb, lb = rs.run_btc(feat, large=True)
    sc = rs.score(rs.load_ref(song), rs.btc_to_beats(pb, lb, bs))
    print(f"{song}: small0 {ts:.1f}s {60/np.median(np.diff(bs)):.1f} BPM, final0 {tf:.1f}s {60/np.median(np.diff(bf)):.1f} BPM, "
          f"beats agree {hit*100:.0f}%, downbeats small/final {len(ds)}/{len(df)}; chords on small0 beats: root {sc['root']*100:.1f}%")
