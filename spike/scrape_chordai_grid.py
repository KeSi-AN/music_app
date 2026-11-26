"""Reads Chord AI's Grid view (beat grid, 8 beats per row) off the phone by scrolling it.

Usage: python scrape_chordai_grid.py <out.json>
Chord AI must be showing a song with Grid view open. Output: list of beats, each the chord name
that starts on that beat, or null when the previous chord continues. "-" means no chord.
"""
import json, os, re, subprocess, sys, time

ADB = [os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"), "-s", "ZD222P585Z"]
GRID_BOTTOM = 1307          # grid area ends here (px)
ROW_PITCH = 174
COL_X0, COL_PITCH = 12, 150

def adb(*a, text=True):
    return subprocess.run(ADB + list(a), capture_output=True, text=text).stdout

def rows_on_screen():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    cells = {}
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        t = re.search(r'text="([^"]*)"', n).group(1)
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        if not t or y2 > GRID_BOTTOM or y1 < 100:
            continue
        w = x2 - x1
        if w not in (126, 576):        # beat cell, or the wide pick-up cell at the very start
            continue
        if y2 - y1 < 100:              # clipped at the top edge: skip, it will be read again
            continue
        col = round((x1 - COL_X0) / COL_PITCH)
        cells.setdefault(y2, {})[col] = t
    # also find empty (no-text) rows: we only know rows that have at least one chord label,
    # so keep y positions and fill the gaps from the pitch
    return cells

def to_rows(cells):
    if not cells:
        return []
    ys = sorted(cells)
    out, y = [], ys[0]
    while y <= ys[-1]:
        row = [None] * 8
        # tolerate a few px of jitter
        for yy in ys:
            if abs(yy - y) <= 8:
                for c, t in cells[yy].items():
                    if 0 <= c < 8:
                        row[c] = t
        out.append((y, row))
        y += ROW_PITCH
    return out

def main(out_path):
    all_rows = []
    prev_sig = None
    for step in range(200):
        rows = to_rows(rows_on_screen())
        sig = [r for _, r in rows]
        if step == 0:
            all_rows = sig[:]
        else:
            # find where the new screen's rows continue the collected list (expect ~4 rows of shift)
            best_k, best_score = None, -1
            for k in range(1, len(sig) + 1):
                overlap = len(sig) - k
                if overlap <= 0 or overlap > len(all_rows):
                    continue
                a, b = all_rows[-overlap:], sig[:overlap]
                score = sum(x == y for x, y in zip(a, b)) / overlap - abs(k - 4) * 0.01
                if score > best_score:
                    best_k, best_score = k, score
            if best_k is None or best_score < 0.9:
                # At the end the grid scrolls less than a full step: the screen may already be
                # entirely inside what we have. Then there is nothing new.
                tail = all_rows[-(len(sig) + 8):]
                inside = any(tail[i:i + len(sig)] == sig for i in range(len(tail) - len(sig) + 1))
                if inside:
                    best_k = 0
                else:
                    print(f"step {step}: weak overlap ({best_score:.2f}); appending all", file=sys.stderr)
                    best_k = len(sig)
            if best_k:
                all_rows += sig[len(sig) - best_k:]
        if sig == prev_sig:
            break
        prev_sig = sig
        adb("shell", "input", "swipe", "600", "1150", "600", str(1150 - 4 * ROW_PITCH), "1500")
        time.sleep(0.8)
    beats = [c for r in all_rows for c in r]
    json.dump({"rows": all_rows, "beats": beats}, open(out_path, "w"), indent=0)
    print(f"{len(all_rows)} rows, {len(beats)} beats, chords: {sorted(set(c for c in beats if c))}")

main(sys.argv[1])
