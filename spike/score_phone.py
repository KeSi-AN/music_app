"""Score the phone's analysis.json against Chord AI's grid (same method as the spike)."""
import json, sys
import run_spike as rs

def phone_beat_labels(path):
    a = json.load(open(path))
    beats = a["beats"]; segs = a["chords"]
    out = []
    bounds = [0.0] + beats
    for i in range(len(bounds) - 1):
        mid = (bounds[i] + bounds[i + 1]) / 2
        lab = next((c for s, e, c in segs if s <= mid < e), "N")
        out.append(rs.parse(lab))
    return a, out

for song in sys.argv[1:]:
    a, est = phone_beat_labels(f"phone/{song}.json")
    sc = rs.score(rs.load_ref(song), est)
    print(f"{song}: phone vs Chord AI root {sc['root']*100:.1f}%  majmin {sc['majmin']*100:.1f}%  "
          f"(x{sc['factor']}, Chord AI = ours {sc['shift']:+d} st); key {a['key']}, {a['bpm']:.1f} BPM, {len(a['chords'])} chord segments")
