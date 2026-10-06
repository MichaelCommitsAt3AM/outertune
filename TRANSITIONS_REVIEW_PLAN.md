# Transitions: Implementation Plan for the Review

2026-10-05 · Implements [TRANSITIONS_REVIEW.md](TRANSITIONS_REVIEW.md). Item numbers (1–6, 5.x, 6.x) refer to that review.

## Goal

When this is done:

- Every surface (in-app player, lyrics, notification, lock screen, Bluetooth, history) agrees on what is playing during a transition.
- The service does no per-tick UI work when nobody is looking.
- Volume has one owner.
- Analysis gets tempo right at any sample rate, in constant memory.
- We know from device measurements, not guesses, whether single-stream mixing is worth building.

The work is split into five tracks. A is quick, independent wins. B and C restructure playback. D is a measured decision on single-stream mixing. E is analysis. A, B and E can run in parallel; C waits for B; D's last steps wait for C.

| Track | What | Review items | Size | Branch |
| --- | --- | --- | --- | --- |
| A | Quick wins | 3, 5.1, 6.1, 6.5 | S–M | `perf/review-quick-wins` |
| B | One owner for volume | 4 | S | `refactor/deck-gain` |
| C | Session-facing player | 2 (absorbs the rest of 3) | L | `feature/mix-session-player` |
| D | Single-stream mixing decision | 1 | measure first, then M–XL | `spike/composition-mixing` |
| E | Analysis | 5.2, 5.3, 5.5 (5.4 is covered by `PlaylistAnalysisScheduler`) | M–L | `feature/analysis-v2` |

**Coordination with your in-progress work:** A3 and all of E touch `LocalPlaylistViewModel`, `AnalysisWorker` and the new `PlaylistAnalysisScheduler`, which have uncommitted changes. Start those steps after that work is committed, and build on its "skip, don't fail" handling and download-then-analyse flow.

---

## Track A: Quick wins

### A1. Pull position while visible (item 3)

Today the service pushes a new `LogicalPlayerState` every 50 ms (mix) or 500 ms (normal) while music plays, screen on or off.

1. Add `fun logicalPositionMs(): Long` and `fun logicalDurationMs(): Long` to `PlaybackEngine`:
   - `SimplePlaybackEngine` reads the player.
   - `MixPlaybackEngine` returns the incoming deck's position after the midpoint, otherwise the active deck's position clamped to the exit point (the logic now in `updateLogicalState`).
2. `LogicalPlayerState` keeps only what changes discretely: metadata, duration, `isTransitionActive`. It is published on item changes, seeks and the midpoint switch, not on a timer.
3. Delete the 500 ms ticker in `MusicService` (`publishSimpleLogicalState` loop and `LOGICAL_POSITION_TICK_MS`).
4. `Player.kt`: a `LaunchedEffect(isPlaying)` that, while the sheet is composed and playing, reads `playerConnection.logicalPositionMs()` every 250 ms into local state (upstream's pattern). `Lyrics.kt` already polls `player.currentPosition`; point it at the same function.
5. `MixPlaybackEngine`'s poller keeps running the state machine but stops calling `updateLogicalStateCallback` on every tick.

**Done when:** with the screen off, a debug counter on `_logicalState` emissions stays flat during playback. The seek bar still moves smoothly, including across a transition. Lyrics follow the incoming song after the midpoint.

**Note:** C replaces A1's engine functions with the facade player. A1 is still worth doing first because it's small, ships the battery win now, and C reuses the same position logic.

### A2. BTrack sample rate (5.1)

1. Patch the vendored BTrack:
   - add a `sampleRate` member (default 44100) set from a new constructor parameter;
   - replace the five `44100` literals (`BTrack.cpp:157, 280, 402, 462, 465`) with it.
2. `native-lib.cpp`: pass the real `sampleRate` to `BTrack(HOP_SIZE, FRAME_SIZE, sampleRate)`.
3. **Analysis version:** add `AnalysisStorage.ANALYSIS_VERSION = 2`, written into the metadata artifact with the duration. `PlaylistAnalysisScheduler` re-queues songs whose stored version is older, since 48 kHz songs analysed before this fix may have the wrong tempo octave.
4. Re-analysis changes the raw grid, so the canonical grid is rebuilt (its inputs change) and saved transitions on those songs may shift. Keep the old raw grid file as `_beats_sync.v1.dat` until the user re-opens the transition, and show the "re-check this transition" flag (Phase 2 deviation, built here).

**Done when:** a small test set of known-BPM tracks (including at least two around 75–90 BPM), decoded at 44.1 and 48 kHz, gives the same BPM within 0.1. It's a device test, since the native code can't run on the JVM; keep the list of tracks and expected BPMs in the PR.

### A3. One query for the playlist's transition chips (6.1)

1. `TransitionDao`: `@Query("SELECT * FROM transitions WHERE fromSongId IN (:ids)") fun transitionsFrom(ids: List<String>): Flow<List<TransitionEntity>>`.
2. `LocalPlaylistViewModel`: a `StateFlow<Map<Pair<String, String>, TransitionState>>` built from the playlist's song IDs (`flatMapLatest` on the song list). Remove `transitionFlows` and `getTransitionState`.
3. `LocalPlaylistScreen`: look up each pair in the map.

**Done when:** saving a transition triggers one query, not one per pair, checked with a Room query log in a debug build.

### A4. Let the mix poller sleep (6.5)

1. `MixPlaybackEngine`: replace the fixed 50 ms loop with a computed delay:
   - IDLE with no transition: wait for a state change (`refreshTransition`, seek, play), using a `Channel`/`MutableSharedFlow` signal.
   - PREFETCH: wake at `exitPoint − ARM_LEAD_MS` (scaled by speed).
   - ARMED: wake at `exitPoint − preroll`.
2. Re-check after any seek or speed change.

**Done when:** a 5-minute song with a transition at 4:00 wakes the poller a handful of times before 3:45, not 4,500.

---

## Track B: One owner for volume (item 4)

1. `DeckAudioProcessor`: add `@Volatile var itemGain: Float` (loudness normalization for the item it is playing), applied with the automation gain and ramped on change.
2. `MixPlaybackEngine`:
   - When an item becomes current on a deck, set that deck processor's `itemGain` from `LoudnessNormalization`, without user volume: for the active deck at start and after a swap, for the standby deck in `prepareStandby`.
   - Remove `targetGainFor`'s user-volume multiply and the `outgoingGain`/`incomingGain` plumbing. The renderer's coarse unmute sets B's player volume to the user volume.
3. `MusicService`:
   - In mix mode, the volume collector applies only `playerVolume` to both decks, with no `isCrossfading` gating.
   - The normalization factor (with its song tagging) stays for normal playback only, where offload means there's no processor.

**Done when:** changing the volume slider mid-transition moves both decks together. No audible level step at the swap, for songs with different loudness and for songs with unknown loudness.

---

## Track C: Session-facing player (item 2)

One stable `Player` for the session and the UI, whose state is the queue as the user experiences it.

### C1. `MixSessionPlayer` facade, mirroring only

1. New `playback/MixSessionPlayer.kt`: `SimpleBasePlayer(Looper.getMainLooper())`.
   - `getState()` builds from the engine's active player: playlist (`MediaItemData` per window, uid = media ID + index), current index, `PositionSupplier` reading the engine, play-when-ready, playback state, repeat and shuffle modes.
   - It listens to the active player (re-attached through the engine's `activePlayer` flow) and calls `invalidateState()` on events.
2. Commands (`handleSetPlayWhenReady`, `handleSeek`, `handleSetRepeatMode`, `handleSetShuffleModeEnabled`, `handleStop`, `handleSetVolume`) go to the engine. Playlist edits (`handleSetMediaItems`, `handleAddMediaItems`, …) go to `QueueBoard`, which keeps writing to the real active deck.
3. `MusicService`:
   - `mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, …)`, set once.
   - `PlayerConnection.player` becomes the facade; internal code (`QueueBoard`, data source, errors) keeps the real deck through `service.player`.

**Done when:** normal-mode behaviour is identical to today across the manual test matrix (M1–M4, M11), with the facade in front.

### C2. Transition semantics in the facade

1. From the crossfade midpoint, the facade reports the incoming song as current and the incoming deck's position (A1's logic moves here). The notification, lock screen and Bluetooth then switch together with the in-app UI.
2. Seeking in the facade maps to the engine's existing rules: after the midpoint it finishes the transition and seeks B; before the midpoint it cancels and seeks A.
3. Skip next/previous during a transition: next finishes the transition; previous cancels it and restarts A.

### C3. Move the UI and delete the side channel

1. `Player.kt`, `MiniPlayer`, `Queue.kt`, `Lyrics.kt`, `PlayerMenu.kt` and the rest of the ~68 `playerConnection.player` reads now see the facade, which is correct during transitions (today they show the outgoing deck).
2. Delete `LogicalPlayerState`, `logicalState`, `seekToLogical`, `seekByLogical`, A1's engine position functions and `publishSimpleLogicalState`.

### C4. Simplify `MusicService`

1. The service listens to the facade instead of every deck, which removes the standby-deck filters in `onMediaItemTransition`, `onPlaybackStateChanged`, `onIsPlayingChanged` and `onPlayerError`.
2. `onActivePlayerChanged` shrinks to engine-internal concerns: sleep timer, skip-silence and repeat now live on the facade.
3. The engine switch no longer touches the session.
4. **Play history:** count a play from the facade's item transitions and play time, instead of a `PlaybackStatsListener` per deck. That fixes attribution across transitions.

**Risks:**
- `SimpleBasePlayer` asserts state consistency (unique uids, position within duration, valid index). Build state in one function with unit tests.
- Those tests need a `Looper`: add Robolectric (`testImplementation`) for C's tests only.
- A large queue makes `getState()` O(n). Cache `MediaItemData` per queue version and rebuild only on timeline changes.

---

## Track D: Single-stream mixing decision (item 1)

### D1. Measure (no code)

1. On at least three devices (one low-end), play 20 transitions covering:
   - same tempo, ±8%, and interval-matched (70/140);
   - streamed and downloaded songs;
   - screen on and off.
2. Collect the `TransitionRenderer` `MixDiagnostics` lines (`adb logcat -s TransitionRenderer`) into a table: unmute error, max error, RMS error, speed changes, re-seeks.
3. **Decision gate:** if the median RMS error is ≤ 0.03 beat and no transition is audibly off, stop here. The phase lock is good enough, and Track D ends.

### D2. Spike: `CompositionPlayer` in the editor preview (only if D1 fails the gate)

1. Expose `androidx.media3:media3-transformer` from the `media` submodule (add a substitution in `settings.gradle.kts` like the existing ones) and suppress `RestrictedApi` for that one class.
2. `CompositionPreview`: a `Composition` with two audio sequences:
   - A, clipped from `anchorA − preroll` to the zone end;
   - `addGap(...)` then B, clipped from its entry, with a speed effect at `initialSpeedB`;
   - `DeckAudioProcessor` + `DeckAutomation` as each item's audio effect, anchored at the clip start.
3. Behind a developer toggle, play the same pair through `PreviewSession` and `CompositionPreview`, and compare by ear and with a recorded output.

**Exit criteria:** the composition is audibly tighter, and it starts within 300 ms of pressing Play.

### D3. Playlists on compositions (only if D2 succeeds; needs C)

1. `MixPlaybackEngine` builds a composition per transition window (A's tail plus B's head) and plays it in place of the two-deck crossfade.
2. Hand back to a normal deck after the zone, at a gap-free boundary: the composition ends on B, and B's deck resumes from the composition's final position, aligned once while A is already silent.
3. The facade (C) hides all of this from the session.

**Do not** move only the editor. Preview and playlist must keep using the same mixing path.

---

## Track E: Analysis

### E1. Two-pass analysis in constant memory (5.2)

1. `AudioDecoder.decodeChunks(context, path, onChunk: (FloatArray, Int) -> Unit)`: the current decoder, emitting mono chunks instead of filling one array.
2. **JNI:** a streaming BTrack API (`createTracker(sampleRate)`, `processFrames(handle, FloatArray)`, `finish(handle): AudioAnalysisResult`). It reuses the existing frame loop, so state lives in the native object between chunks.
3. **Pass 1 (streaming):** feed BTrack, accumulate the waveform RMS envelope, and keep three band envelopes (low ≤150 Hz, mid, high) at 1 kHz. That's about 3 MB for 4 minutes, versus about 42 MB for the full song.
4. **Pass 2 (on the envelopes):** the grid offset search (`bassPeakIndex` on the low envelope, 1 ms resolution) and `BarDetector` rewritten to read band envelopes instead of filtering raw PCM.
5. The exact duration comes from the frame count.

**Done when:** peak memory during analysis is under 10 MB above baseline, and the outputs for a test set match today's within 1 ms (grid) and the same downbeat offset.

### E2. Variable-tempo grids (5.3)

1. Worker: keep BTrack's per-beat times. Smooth them with a running median of intervals and re-anchor each beat to the nearest bass peak (E1's low envelope). Store the result as the raw grid.
2. Store `tempoVariation` (the standard deviation of local tempo, %). Show a warning in the editor above 1%.
3. `TransitionMath.syncParameters`: for close tempos, use the ratio of local intervals at the two anchors instead of display BPMs.
4. Bump `CanonicalGrid.VERSION` and the analysis version (A2). Saved transitions keep their absolute exit/entry times; flag them for re-check, as in A2.

**Done when:**
- A live-drummed test track's grid stays within 15 ms of its kicks across the whole song (today it drifts).
- Electronic tracks are unchanged within 1 ms.

### E3. Key detection (5.5)

1. Run `KeyDetector.detectKey` over the test set in a debug build first. It's native code that has never run, and a native crash can't be caught.
2. If it's clean, call it in the worker after BPM analysis (in pass 1's chunked form, or on a 60 s window as `extractAnalysisWindow` already does). Store `song.key = key.camelot`.
3. Editor: show both keys, and mark harmonically compatible pairs (same number, ±1, or A↔B on the Camelot wheel).

---

## Order

1. A1, A4 (now); A2, A3 once your analysis and playlist work is committed.
2. B.
3. C1 → C2 → C3 → C4.
4. D1 can run any time on the current build; D2 and D3 only if D1 fails its gate (D3 after C).
5. E1 → E2 → E3, in parallel with B and C.

## Testing

| Track | Unit tests | Device checks |
| --- | --- | --- |
| A | Engine position functions (pure parts); poller wake schedule | Screen-off emission counter; BTrack BPM table |
| B | Processor gain ramps with `itemGain` | Volume slider mid-transition; level at swap |
| C | Facade state building (Robolectric); seek and skip mapping | Manual test matrix M1–M11 plus notification and lock screen during a transition |
| D | — | `MixDiagnostics` table; A/B listening |
| E | Envelope-based grid and bar detection against stored fixtures | Peak memory; grid against kicks on the test set |
