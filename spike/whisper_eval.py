"""Whisper spike: transcription accuracy against LRCLIB lyrics (character error rate, Devanagari only),
plus how much of the output is loops ("repeated") or the wrong script."""
import json, sys, re, unicodedata

def letters(s):
    # keep Devanagari letters and signs; drop everything else
    return "".join(ch for ch in s if "ऀ" <= ch <= "ॿ" and unicodedata.category(ch)[0] in "LM")

def cer(ref, hyp):
    # edit distance on characters, with the reference able to start anywhere (covers / partial songs)
    prev = list(range(len(hyp) + 1))
    for i, a in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, b in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a != b))
        prev = cur
    return prev[-1] / max(1, len(ref))

def main(song, run):
    ref = letters(json.load(open(f"tmp/{song}_lrclib.json", encoding="utf-8"))["plainLyrics"])
    out = json.loads(open(f"tmp/{song}_{run}.json", "rb").read().decode("utf-8", "replace"))
    segs = out["transcription"]
    text = " ".join(s["text"] for s in segs)
    hyp = letters(text)
    other = sum(1 for ch in text if ch.isalpha() and not ("ऀ" <= ch <= "ॿ"))
    words = text.split()
    loops = sum(1 for i in range(2, len(words)) if words[i] == words[i - 1] == words[i - 2])
    print(f"{song:12s} {run:28s} CER {100 * cer(ref, hyp):5.1f}%  ref {len(ref)} chars, got {len(hyp)}, "
          f"other-script letters {other}, looped words {loops}, segments {len(segs)}")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
