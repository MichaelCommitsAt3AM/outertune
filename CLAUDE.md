# CLAUDE.md

A fork of [OuterTune](https://github.com/DD3Boh/OuterTune) (Android YouTube Music / local music player, Kotlin + Jetpack Compose + Media3) that adds a **mix mode**: beatmatched DJ-style transitions between songs in a playlist, inspired by Spotify Mix, plus a transition editor and on-device audio analysis.

## Read this first: code quality

This project was built while its author was still learning Android, audio programming and DSP. Much of it, especially the transitions feature and the code around it, was written by trial and error, so:

- It doesn't always follow best practices, and the code quality is uneven.
- An existing pattern is not proof that it is the right one. Check before copying it.
- Comments sometimes describe intent or history rather than what the code does. Trust the code.
- When you find something wrong, say so and suggest a better approach; don't quietly preserve it.

[TRANSITIONS_AUDIT.md](TRANSITIONS_AUDIT.md) and [TRANSITIONS_REVIEW.md](TRANSITIONS_REVIEW.md) list known problems and what was done about them. Check them before reworking that area.

## Build and test

Gradle must run on **JDK 21** (the system default JDK is too new):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk PATH=/usr/lib/jvm/java-21-temurin-jdk/bin:$PATH
G="./gradlew -Dorg.gradle.java.installations.auto-detect=false -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-21-temurin-jdk --max-workers=2"

$G :app:compileFullDebugKotlin          # quick compile check
$G :app:testFullDebugUnitTest           # JVM unit tests (incl. Robolectric)
$G :app:assembleCoreDebug               # debug APKs (per ABI + universal)
adb install -r app/build/outputs/apk/core/debug/OuterTune-*-core-arm64-v8a-debug-*.apk
```

- Flavors `core` / `full` × build types `debug` / `userdebug`. Use `core` (`full` needs extra FFmpeg setup). There is no bare `testDebugUnitTest`.
- **Always limit Gradle to 2 workers** (`--max-workers=2`) on every Gradle run: compiles, tests and APK builds. The development machine has limited memory, and builds with more workers have crashed it. Don't run two Gradle builds at once.
- `debug` installs as `com.dd3boh.outertune.debug`; `userdebug` (profileable, used for profiling) as `com.dd3boh.outertune`. They are separate apps with separate data. The author uses the **debug** build day to day.
- `adb install -r` force-stops the running app. Tell the user before reinstalling, because to them it looks like a crash.
- Logs: `adb logcat -s SyncUtils TransitionRenderer MixPlaybackEngine AnalysisWorker`. One `TransitionRenderer` line per transition reports beatmatch quality.
- The `llvm-strip` errors about `libpython.zip.so` during builds are expected (yt-dlp's bundled Python); they don't fail the build.

## Layout

| Path | What |
| --- | --- |
| `app/` | The app |
| `innertube/` | YouTube Music API client (requests, response models) |
| `media/` | **Media3 built from source** (git submodule, substituted in `settings.gradle.kts`) |
| `app/src/main/cpp/` | Native analysis: vendored and patched BTrack (tempo; now takes the real sample rate), kiss_fft, libkeyfinder |
| `kugou/`, `lrclib/` | Lyrics providers |

### Mix mode / transitions (`app/src/main/java/com/dd3boh/outertune/`)

- `transition/engine/`: the shared mix engine, used by both the editor preview and playlist playback:
  - `TransitionRenderer` runs a transition on two players.
  - `MixController` and `PhaseController` make the per-tick phase-lock decisions (pure Kotlin).
  - `DeckAutomation` and `DeckAudioProcessor` shape volume, filters and ducking inside each deck's audio pipeline.
  - `DeckPair` and `DeckFactory` build and hold the two decks.
  - `BeatGridRepository` and `CanonicalGrid` provide the one beat grid per song.
  - `MixTuning` holds every constant.
- `transition/math/TransitionMath`: plan maths (pure). `transition/model/`: plan, config and mode enums.
- `transition/editor/`: editor data loading (`TransitionEditorEngine`) and preview (`PreviewSession`).
- `playback/`:
  - `MixPlaybackEngine` is the mix-mode state machine and owns all transition state.
  - `SimplePlaybackEngine` handles normal playback.
  - `MixSessionPlayer` is the single player the media session and UI use.
  - `MusicService` is the playback service.
- `utils/analysis/`: `AnalysisWorker` (WorkManager), decoder, bar detection, beat-grid normalisation, analysis files on disk.
- `utils/SyncUtils`: YouTube library sync.

## Conventions

- **Branches:** do large changes on a feature branch off `dev`, not on `dev` itself.
- **Commits:** the author often has uncommitted work in progress in the same tree. Stage and commit only the files you changed. Check `git status` and `git diff` before every commit, and never revert their changes.
- **Docs:** write plans, audits and reviews as Markdown files in the repo root (e.g. `TRANSITIONS_PLAN.md`).
- **Logging:** in the mix engine and analysis code, use `import com.dd3boh.outertune.utils.DebugLog as Log`. Debug and info messages are debug-build only; use the lambda form for interpolated messages.
- **Tuning:** put new mix/beatmatching constants in `MixTuning`, not inline.
- **Tests:** keep transition maths and control logic pure Kotlin with JVM unit tests. `MixSessionPlayer` has Robolectric tests. Run the unit tests after touching any of these.
- **Database:** Room, currently version 28, with hand-written migrations in `db/MusicDatabase.kt`. Exported schemas live in `app/schemas/`. Adding a migration means bumping the version, writing the migration, and checking the new exported schema against it.

## Pitfalls

- Media3 is the `media` submodule, not a Maven artifact. Read its source there when checking an API.
- CMake gathers native sources with `file(GLOB)` and no `CONFIGURE_DEPENDS`. After adding or removing native files, delete `app/.cxx` and `app/build/intermediates/cxx`.
- Mix decks must not use offload or float output: both bypass the audio processors, including the time-stretcher that beatmatching needs.
- YouTube response models in `innertube` are strict: a field YouTube leaves out makes the whole request fail. Give optional-looking fields defaults, and log `Result` failures instead of only handling `onSuccess`.
