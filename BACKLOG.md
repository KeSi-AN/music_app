# Fixes and improvements

These are outside the phase plan (`BUILD_PLAN.md`). Newest first within each section.

## To test (reminder)

### Sing and test Phase 5
- **Who:** you, as asked on 2026-11-27.
- **How:**
  1. Plug in headphones and open a song that has a karaoke track (Uska Hi Bana or Vellake). Then tap Karaoke, then **Sing**.
  2. Sing a verse or two with the vocals slider on "Karaoke", then tap **Done singing**.
- **What to check:**
  - Your line follows the melody bars without lagging.
  - The score makes sense.
  - "My voice" on the home screen shows "· 1 songs" afterwards.
- **Without headphones:** the mic also hears the speaker, and at low volume it hears nothing.
- **Also check:** a friend singing with "Learn from it" switched off doesn't change your profile.

### Try Phase 6 (auto-fit) after the range test
- **Why it's waiting:** your voice profile is empty, so Riyaz has no range to fit songs to yet. Checked on the phone on 2026-11-27 with a temporary test range, which was then removed.
- **How:**
  1. Do the range test (My voice, on the home screen).
  2. Open a song that has a karaoke track (Uska Hi Bana, Vellake).
- **What to check:**
  - The "Fit to your voice" card suggests a key and capo that feel right, and its reason makes sense.
  - Songs you've already set to a key keep that key; the card only offers **Use**.
  - A new song, never changed by you, gets the fit filled in once its karaoke track is made.
  - If you keep moving the suggested key the same way (for example 1 lower), after three songs the suggestion includes that habit.

### Try Phase 7 (songbook)
- **What to try:**
  1. Copy a chord sheet from a website or WhatsApp and share it to Riyaz (or Songbook, then Paste).
  2. Open a song in Riyaz, tap **Chord sheet** next to "Chords", then play it from the sheet's play button: the sheet scrolls along with the song.
  3. Make a setlist and step through it.
- **Checked on the phone on 2026-11-27** with Vellake, Uska Hi Bana and a pasted Ultimate Guitar-style sheet. All test sheets and the test setlist were then removed.

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

### Auto-fit: what Phase 6 doesn't do yet
- **Song type (genre/mood):** the plan said YAMNet. For now it only shows the pace (slow / mid-tempo / upbeat) from the BPM.
- **Language without lyrics:** it comes from the lyrics' script, common words, or a language word in the title. Songs whose lyrics haven't been fetched show none (e.g. Seethakaalam).
- **A suggestion before the karaoke track:** the song's vocal range comes from the melody, which needs the instrumental (2–3 min). A quick separation of just 60 s of the song would give the range much sooner.

### Chords over the lyrics in karaoke (Ultimate Guitar style)
- **Want:** a toggle on the karaoke screen that shows each chord above the word where it changes, like a chord sheet. It follows your key, capo and Simple settings, and the current line stays highlighted.
- **How:** the chord timeline (Phase 2) and the line timings (synced or tapped) already exist.
  - **Partly done in Phase 7:** the songbook's "Chord sheet" button already places each chord over the word where it changes (in proportion to its time along the line) and follows the song while it plays. What's left is showing that inside the karaoke screen itself.
  - Tapped timings aren't used yet: the generator only uses lyrics that came synced from LRCLIB.
  - For exact word positions, align words to the audio. Whisper with word timestamps would do it; so would the user tapping words.
- **Needs:** timed lyrics. For untimed lyrics, show the line's chords as a row of names above it.

### Faster or better vocal removal
- **Now:** MDX 9482 on the phone's CPU. On the Edge 50 Neo that took 2 min for Vellake (3:37) and 3 min for Uska Hi Bana (4:33), with a peak of about 1.1 GB of memory.
- **Options:**
  - Use the GPU through LiteRT, as Chord AI does: convert the model to TFLite and use the GPU delegate.
  - Offer the cleaner Kim_Vocal_2 model (13.8 dB, about 4× slower) as a "best quality" choice.
  - Keep separating when the app goes to the background, using a foreground service.

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
