# Transitions: Review of the Editor Preview

2026-10-06 · Covers `TransitionEditorScreen`, `TransitionEditorViewModel`, `PreviewSession`, `TransitionEditorEngine`, `WaveformView`, and the parts of the engine a preview runs through (`TransitionRenderer`, `MixController`, `PhaseController`, `DeckAutomation`, `DeckAudioProcessor`, `TransitionMath`). Follows [TRANSITIONS_REVIEW.md](TRANSITIONS_REVIEW.md); items already listed there aren't repeated.

## Summary

The preview runs the same renderer as playlist playback, as intended. Most of what's below is correctness, not speed:

1. **B entering near its start is played from the wrong place** (engine, playlists too). If B's entry point is within about 3 s of the start of the song, B is ahead of where it should be when it becomes audible, and its automation is ahead too.
2. **The preview can get stuck "playing"** if A ends or errors before the preroll. Nothing ends the lead-in loop or the renderer then.
3. **Rotating the screen throws away unsaved edits**, because `loadData` runs again and restores the saved row.
4. **Track B shows Track A's beat markers**, so B's downbeats are drawn in A's bar phase and snapping uses A's beats.
5. **The preview starts B with a cold seek from 0**, unlike playlists, so its first ticks beatmatch worse than a playlist does.

The performance items are small: the whole screen recomposes at 50 Hz while playing, and the waveforms draw several samples per pixel at wide zooms.

## Correctness

### C1. B entry near the song start (engine) — high

**Where.** [TransitionRenderer.kt:58](app/src/main/java/com/dd3boh/outertune/transition/engine/TransitionRenderer.kt#L58), [MixController.kt:45-49](app/src/main/java/com/dd3boh/outertune/transition/engine/MixController.kt#L45-L49), [TransitionMath.kt:156-159](app/src/main/java/com/dd3boh/outertune/transition/math/TransitionMath.kt#L156-L159).

**What happens.** The renderer starts at the preroll, 3 s of A before the zone, and seeks B to `targetPositionBMs(...)`. When B's entry is less than about 3 s into B (for example its first beat, which is the most common entry), that target is negative. `getTimestampForBeat` clamps it to 0, so B starts playing from 0 right away, up to 3 s ahead of its target:

- The phase error in preroll is several beats negative. `shouldReseek` fires, seeks B back to 0, and B is ahead again. After `MAX_RESEEKS` (2), the P controller clamps B at `base × 0.8`. Over the remaining preroll that recovers only about 0.5 s.
- At the unmute, B is still about 2–2.5 s (~5 beats) ahead. The crossfade stage wraps the error to the nearest beat, so B locks in phase but off by whole beats. The intro is skipped and B's downbeats don't line up with A's.
- `DeckAutomation` for B computes progress from B's own position, so B's fade, bass swap and filter sweep also run ~5 beats early.
- If the zone starts before B's first beat (possible by dragging B right, down to anchor −zone/2), B plays early for part of the zone itself.

**Fix.** Add a "waiting" step for B. If B's target position is negative, keep B paused at 0 (or at its first sample) and call `play()` when A reaches the position where the target crosses 0. Get that position from `getTimestampForBeat(gridA, anchorA + (beatOf(gridB, 0.0) − anchorB) × gridScalar)`. `MixController.step` should skip speed and re-seek decisions while B is waiting. A unit test on `MixController` with `anchorBeatB = 0` and `grid[0] = 0.2` covers it.

### C2. The preview can hang in "playing" — medium

**Where.** [PreviewSession.kt:106-112](app/src/main/java/com/dd3boh/outertune/transition/editor/PreviewSession.kt#L106-L112), [TransitionRenderer.kt:80-87](app/src/main/java/com/dd3boh/outertune/transition/engine/TransitionRenderer.kt#L80-L87).

- **Lead-in.** `while (a.currentPosition < prerollStartMs)` has no exit other than reaching the preroll. If the zone starts past A's end, `currentPosition` stops at the duration and the loop never ends, with the UI showing Pause over silence. Snapping doesn't prevent that zone placement. It clamps the *centre* beat to A's last marker, and the zone starts `margin` beats after the left edge, which at 16–32 bars is 5–10 s. A player error (file moved or deleted) freezes the position the same way.
- **Renderer.** `budgetMs` counts only time A is playing, and the only exit checks are `STATE_ENDED` or a changed media item. If A errors (`STATE_IDLE` with a `playerError`), it is neither playing nor ended, so the loop ticks at 50 Hz forever. This applies to playlists too: `crossfadeJob` never completes.

**Fix.** In the lead-in, also exit on `playbackState == STATE_ENDED || playerError != null`. In the renderer, treat `STATE_IDLE` and `playerError != null` like `OUTGOING_ENDED`, and add a wall-clock cap (e.g. 3× the budget) next to the playing-time budget. In the editor, clamp the offsets on drag end so `anchorBeatA + bars×4` stays within A's grid. Also set `PreviewSession.error` from a `Player.Listener.onPlayerError` on both decks; today only a prepare timeout reports one.

### C3. Rotation discards unsaved edits — medium

**Where.** [TransitionEditorScreen.kt:61-65](app/src/main/java/com/dd3boh/outertune/ui/screens/TransitionEditorScreen.kt#L61-L65), [TransitionEditorViewModel.kt:114-140](app/src/main/java/com/dd3boh/outertune/viewmodels/TransitionEditorViewModel.kt#L114-L140).

`MainActivity` doesn't declare `configChanges`, so rotation (or a dark-mode or locale switch) recreates the composition. The ViewModel survives, but `LaunchedEffect(songAId, songBId)` runs `loadData` again. That reloads the artifacts, calls `restore()` from the database row (overwriting offsets and modes the user changed), resets `originalState`, so `hasChanges` goes false, and calls `preview.load()`, which stops a running preview and re-prepares both decks.

**Fix.** Make loading idempotent. Either read the ids from `SavedStateHandle` (they are nav arguments) and load in `init`, or return early from `loadData` when the same pair is already loaded.

### C4. Track B is drawn and snapped with Track A's markers — medium

**Where.** [TransitionEditorScreen.kt:237-247](app/src/main/java/com/dd3boh/outertune/ui/screens/TransitionEditorScreen.kt#L237-L247), [TransitionEditorEngine.kt:49](app/src/main/java/com/dd3boh/outertune/transition/editor/TransitionEditorEngine.kt#L49).

Both `WaveformView`s get `beatMarkers`, which is built from A's waveform, `timeSignature` and `downbeatOffset`. On B:

- The green downbeat ticks follow A's bar phase, not B's. Lining up "downbeats" on screen can put B's bar one to three beats off whenever the two songs' `downbeatOffset`s differ.
- Snapping (`nearestMarkerBeat`) only reaches beats up to A's length. When B is longer than A, B can't be centred past A's last beat.
- With `gridScalar = 1.5`, B's beats fall at multiples of 1.5 A-beats, but snapping is to integer A-beats, so B's anchor can land between B's beats (see also P5).

**Fix.** Build a second marker list in `loadArtifacts` from B's waveform, `timeSignature` and `downbeatOffset`, with `beatIndex = i × scalarB`, and pass it to the B view. `EditorArtifacts` gains a `beatMarkersB` field.

### C5. The preview starts B with a cold seek — medium (preview/playlist parity)

**Where.** [PreviewSession.kt:74](app/src/main/java/com/dd3boh/outertune/transition/editor/PreviewSession.kt#L74), compared with [MixPlaybackEngine.kt:491-494](app/src/main/java/com/dd3boh/outertune/playback/MixPlaybackEngine.kt#L491-L494).

Playlists prepare B at `entry − preroll` and at `initialSpeedB`, so the renderer's first seek moves within buffered audio. The preview prepares B at 0 and speed 1. The renderer's seek is then a full flush and re-decode, and `SEEK_SETTLE_MS` (50 ms) often isn't enough. While B rebuffers, the preroll controller sees the error grow, pushes B to +20%, and then spends a re-seek (out of 2) once B starts. Preview diagnostics are therefore worse than a playlist's for the same transition, which undermines the editor as the reference.

**Fix.** In `play()`, during the 1 s lead-in, seek B (paused) to `MixController(plan, plan.initialSpeedB).targetPositionBMs(prerollStartMs)`, set its speed to `initialSpeedB`, and wait for `awaitReady(b)` before starting the renderer. The lead-in already has the time.

### C6. Edits during a running preview are only half applied — low

The plan is captured once in `togglePlayback()`; only the modes are read live. Dragging a waveform or changing bars mid-preview changes the green box but not what you hear. The playhead also jumps, because `track1OffsetPixels` updates on drag end while the audio follows the old anchor. **Fix:** in the ViewModel, restart the preview with a fresh plan when offsets or `barsCount` change while playing, or stop it.

### C7. Legacy restore ignores the grid scalar for B — low

**Where.** [TransitionEditorViewModel.kt:160-165](app/src/main/java/com/dd3boh/outertune/viewmodels/TransitionEditorViewModel.kt#L160-L165).

`entryBeatB` is in B's own beats, while `_track2OffsetBeats` is in A-beat units (`calculatePlan` divides by `gridScalar`). For interval-matched legacy rows (|ΔBPM| > 15), B is restored at the wrong place. **Fix:** `entryBeatB × syncParameters(...).gridScalar − startShiftBeats`.

### C8. The preview stops dead at the end of the zone — low (UX)

When the renderer returns `COMPLETED`, `finally` pauses both decks right away, so the preview cuts off on the last beat of the zone. The result of a mix is easier to judge with B running on its own for a bar or two. **Fix:** after `COMPLETED`, keep B playing (A is already at volume 0) for ~2 bars of B before pausing. Run the delay before the `finally` cleanup.

### C9. The preview keeps playing in the background — low

Nothing stops the preview when the app goes to the background, and it holds audio focus. Stop it on `Lifecycle.Event.ON_STOP` (a `LifecycleEventEffect` in the screen calling `viewModel.stopPreview()`).

## Performance

### P1. The whole screen recomposes at 50 Hz while previewing

`playbackBeatMarker` is collected at the top of `TransitionEditorScreen`, and `PreviewSession` emits a new `State` every 20 ms tick. Each tick recomposes the screen body and `WaveformsSection`. Strong skipping (Kotlin 2.2) saves the `WaveformView`s, but not the parent bodies or the playhead `Canvas`.

**Fix.** Read the beat in the draw phase only. Pass `playheadBeat: () -> Float?` (or the `State<Float?>`) into `WaveformsSection`, and draw the playhead in a `Canvas`/`drawWithContent` that calls it inside the draw lambda. That turns each tick into a single redraw with no recomposition. `isPlaying` already only changes on start and stop.

### P2. Waveforms draw several samples per pixel when zoomed out

`SAMPLES_PER_BEAT = 64`. At 32 bars, `pixelsPerBeat` is ~6 px, so each visible beat draws 64 strokes in 6 px. That's about 10 strokes per pixel, or roughly 10k–15k line segments per waveform per frame while dragging. **Fix:** in `WaveformView`, step through samples with `stride = max(1, samplesPerBeat / pixelsPerBeat)` and take the max over each stride. Or precompute 2–3 levels (64/16/4 per beat) in `TransitionEditorEngine` and pick one by zoom.

### P3. `BeatSample` objects

A 5-minute song at 128 BPM gives ~41k `BeatSample` objects per track (~1 MB each, plus GC churn when loading). Two `FloatArray`s (beat index and amplitude), or a single `FloatArray` of amplitudes with the beat index implied by `i / SAMPLES_PER_BEAT × scalar`, would be smaller and faster to search. The same applies to `generateBeatMarkers`.

### P4. Mix-curve overlay

The green-box `Canvas` evaluates `getMixState` 102 times and rebuilds two `Path`s on every redraw. That's cheap, but if P1 isn't fixed it happens 50 times a second. Wrap it in `Modifier.drawWithCache` keyed on the three modes. While you're there:
- `DYNAMIC_SIDECHAIN` draws as two flat lines, because no positions or grids are passed. Pass a synthetic grid (e.g. one beat per 1/`bars×4` of the width) so the ducking shows.
- The overlay shows volume only. A faint bass curve and filter curve would show what the EQ and effect modes do.

### P5. Engine notes for the preview path

- `DeckAudioProcessor` processes per sample even when the state is a constant `SILENT` (B for the whole preroll) or `NEUTRAL` with gain 1 and no filters (A before the zone). Detect a constant block and zero-fill or bulk-copy it. This complements the bulk-read item in TRANSITIONS_REVIEW.md §6.
- `syncParameters` uses display BPMs within ±15 BPM but whole-song grid averages beyond that, and in both cases ignores local tempo at the anchor. Using the local interval around each anchor (`grid[anchor+1] − grid[anchor]`, averaged over a bar) gives a better `baseSpeed`, so the controller starts closer and makes fewer speed changes.
- The `1.5` multiplier candidate in `syncParameters` produces 3:2 alignments where integer A-beats don't fall on B's beats (C4). Unless 3:2 is wanted, drop it, or snap B to its own beat markers once C4 is fixed.
- `EqMode.ONSET_BASS_SWAP` steps A's bass from 0 dB to −26 dB in one 128-frame block at progress 0, and coefficients change with the filter state kept. Ramping it over ~1 beat (as `CENTRE_BASS_SWAP` does) avoids a thump.
- `TransitionRenderer` sets both `playWhenReady = true` and calls `play()` on B. One is enough.

## Suggested order

1. **C1** (engine, also affects playlists) with a `MixController` unit test.
2. **C2** and **C5**: robustness and parity of the preview; both are small.
3. **C3** and **C4**: editor correctness.
4. **P1** and **P2**.
5. The rest as convenient.

## Status

Implemented on `fix/preview-review`: C1–C9, P1, P2, P4 and the processor fast paths in P5. A zone past A's end is prevented by limiting how far Track A can snap (`WaveformView.maxCenterBeat`), and a running preview exits if A ends anyway.

Not done:
- **P3** (`FloatArray` waveforms): a wider refactor for a memory win only. P2 already removes the drawing cost.
- **Local tempo in `syncParameters`**, **dropping the 1.5 multiplier**, and **ramping `ONSET_BASS_SWAP`**: all three change how saved transitions sound, so they need listening tests first.
