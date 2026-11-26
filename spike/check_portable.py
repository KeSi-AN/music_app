"""Portable pipeline vs librosa/BTC reference: CQT similarity, BTC frame agreement, beats, final scores."""
import time, numpy as np, librosa, soundfile as sf
import run_spike as rs, portable as P

chords = P.Chords(threads=1)
cqt = P.CQT()
for song in rs.SONGS:
    # start from the original 48 kHz Opus decode (as the phone will), not the 22050 wav
    y48, sr48 = librosa.load(rs.HERE / "audio" / f"{song}.webm", sr=None, mono=True)
    t0 = time.time(); y = P.resample(y48, sr48); t_rs = time.time() - t0
    y_ref, _ = librosa.load(rs.HERE / "audio" / f"{song}.wav", sr=rs.SR, mono=True)
    t0 = time.time(); C = cqt(y); t_cqt = time.time() - t0
    L = np.exp(rs.btc_features(y_ref))                     # librosa CQT magnitude (BTC's own pipeline)
    n = min(C.shape[1], L.shape[1])
    ratio = np.median(C[:, :n] / (L[:, :n] + 1e-9), axis=1)
    p_ours = chords.probs(C); p_ref = chords.probs(L)
    m = min(len(p_ours), len(p_ref))
    agree = np.mean(p_ours[:m].argmax(1) == p_ref[:m].argmax(1))
    t0 = time.time(); env, fps = P.onset_env(y); bpm, _ = P.tempo(env, fps); beats = P.beat_track(env, fps, bpm); t_beat = time.time() - t0
    labels = P.beat_sync_labels(p_ours, beats)
    est = [rs.parse(l) for l in labels]
    sc = rs.score(rs.load_ref(song), est)
    key = rs.name(rs.key_from_chords(est))
    print(f"{song}: resample {t_rs:.1f}s, CQT {t_cqt:.1f}s (ours/librosa per-bin ratio median {np.median(ratio):.3f}, "
          f"range {ratio.min():.2f}-{ratio.max():.2f}), BTC frames agree with librosa-CQT run {agree*100:.1f}%")
    print(f"   beats {t_beat:.1f}s, {bpm:.1f} BPM, {len(beats)} beats; chords vs Chord AI root {sc['root']*100:.1f}% "
          f"majmin {sc['majmin']*100:.1f}% (x{sc['factor']}, shift {sc['shift']:+d}); key {key}; bar phase {P.bar_phase(labels)}")
