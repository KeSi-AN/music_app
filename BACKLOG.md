# Fixes and improvements

These are outside the phase plan (`BUILD_PLAN.md`). Newest first within each section.

## Fixes

### Slow first load of a new song
- **Problem:** preparing a song the first time takes **11–19 s**, about 4 s per minute of music. Measured on the Edge 50 Neo on 2026-11-27. Opening it again is instant, because the result is cached.
- **Cause:** Android's AAC decoder (`c2.android.aac.decoder`) runs only 15–20× real time on this phone. The faster in-process version isn't available to apps. Everything else is quick: the waveform takes 70 ms and the engine loads in 30 ms.
- **Options:**
  1. **Decode in parallel chunks.** Run 3–4 decoders, each on its own slice of the song. Each starts about 1 s early so it's warmed up, and output is placed by timestamp. Expect 3–4× faster.
     - Must verify the joins are seamless: compare the result byte for byte with a single-decoder decode.
  2. **Start playing while decoding.** Playback can begin after about 1 s. The engine would play from a file that's still growing, with a "decoded up to" marker. Seeking past that point waits.
  3. Both. Option 2 makes the wait feel short, and option 1 makes it actually short.
- **YouTube songs:** measured 2026-11-27, they wait on decoding too (Opus runs at the same ~16× real time) plus the download:
  - Looking up the stream takes about 2.3 s.
  - The download itself runs at about 1 MB/s on the phone.
  - **Example:** a 4:33 video took 25 s in total, about 8 s to download and about 17 s to decode.
  - Downloading with several range requests in parallel may help; measure that before relying on it.
- **Done when:** a new 4–5 min song is playable within about 2 s, and fully prepared within about 5 s.

## Improvements

### More accurate beats (beat_this)
- **Now:** beats come from a classic onset + DP tracker. Chords are almost as good on its beats, but on slow songs it often counts **double time**. Naina Morey shows 144 BPM instead of 72; tapping the BPM switches it.
- **Better:** beat_this, the beat model Chord AI ships. It got the tempo right on all 3 test songs, but in ONNX Runtime it takes 13–27 s per song on the laptop.
- **Options:**
  - Run it in the background after the chords appear.
  - Convert it to LiteRT with a GPU delegate, as Chord AI does.

### Smaller app download
- ONNX Runtime adds about 34 MB, or about 12 MB compressed, to run one 13 MB chord model.
- **Options:** a reduced-operator ONNX Runtime build, or converting BTC to LiteRT. Either one needs its accuracy re-checked against `spike/score_phone.py`.

### Keep playing in the background
- **Now:** pressing Back on the home screen closes the app, and the music stops. Turning the screen off works only because the app keeps the screen awake while playing.
- **Want:** a media notification with play/pause, and playback that carries on with the screen off or when switching apps (a foreground media service plus a MediaSession). That also gives Bluetooth-headset and lock-screen controls.

### Guest mode
- **Why:** friends will sing on your phone at jam sessions and karaoke. Their voices must not teach *your* voice profile (Phases 4–6), and their key changes must not overwrite the key saved for a song.
- **What it does:**
  - A quick **"Guest"** switch on the player and karaoke screens, clearly visible while it's on.
  - While on:
    - Nothing is learned into your profile.
    - Key and speed changes aren't saved to the song.
    - Auto-fit uses a neutral setting, or a quick range check for the guest.
  - Optional **named guest profiles** for regulars (for example a bandmate). Each one learns separately and can be deleted.
  - It switches back to you automatically after a while of inactivity, or when the app restarts. That way, forgetting it can't quietly pollute your profile.
- **Depends on:** the voice profile (Phase 4). The switch itself can arrive earlier, as "don't save changes".
