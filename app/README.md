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
