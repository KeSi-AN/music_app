# Riyaz (Android)

Personal music app. See `../BUILD_PLAN.md` for the plan and `../TEARDOWN.md` for the research.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. It's signed with the debug key and sideloaded, never published.

## Layout

- `app/src/main/cpp`: the audio engine.
  - Oboe output.
  - Signalsmith Stretch for key and speed, running on a worker thread that fills a small ring buffer.
  - `third_party/` holds the vendored MIT headers (versions are in `VERSIONS.txt`).
- `audio/AudioDecoder.kt`: MediaCodec decode to 16-bit stereo PCM, plus waveform peaks.
- `audio/SongLibrary.kt`: decoded songs in `files/songs/<id>/`.
  - The key and speed you last used are saved per song.
  - Only the 8 most recent songs are kept.
- `PlayerViewModel.kt` and `ui/PlayerScreen.kt`: the player.

## Testing on the phone

A debug build adds `RenderTestReceiver`, a hook that renders a clip of the most recent song with given settings. Release builds don't include it.

```powershell
adb shell am broadcast -n com.riyaz.app/.RenderTestReceiver --ef start 60 --ef secs 20 --ef semis -3 --ef tempo 1 --ez natural false --es name k-3
adb pull /sdcard/Android/data/com.riyaz.app/files/render/ test/render/
```

Then, with `source_60s.wav` cut from the original song using ffmpeg:
- `python test/chroma_check.py test/render orig k-3` measures the actual key shift and counts clipped samples.
- `python test/analyze_renders.py test/render` checks timing.

Logcat tags:
- `RiyazEngine` logs the stream setup, plus position every 5 s of song time while playing (used to check speed).
- `Riyaz` logs which decoder was used and import timings.
