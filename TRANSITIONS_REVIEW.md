# Transitions: Review After Phases 0–4

2026-10-05 · Follows [TRANSITIONS_AUDIT.md](TRANSITIONS_AUDIT.md) and [TRANSITIONS_PLAN.md](TRANSITIONS_PLAN.md).

## Summary

The remaining weaknesses are mostly architectural, not bugs. Three approaches limit the feature, in this order:

1. **Beatmatching two independent players with a feedback loop.** Even with the shared controller, B's timing is steered through ExoPlayer's reported positions and buffered speed changes. Phase 4 made the *shape* of a transition sample-accurate; its *timing* is still only as good as the phase lock. Mixing both songs in one audio stream would make timing exact too.
2. **Two sources of truth for "what's playing".** The in-app player shows the incoming song from the crossfade midpoint, while the media session (notification, lock screen, Bluetooth, Android Auto, history) still reports the outgoing song until the decks swap. Several workarounds exist only to paper over this split.
3. **Analysis built around one constant tempo and the wrong sample rate.** BTrack assumes 44.1 kHz, but most YouTube audio decodes at 48 kHz. The grid is then forced to one steady tempo, which is wrong for live-drummed or tempo-varying songs.

Below, each issue has what we do now, why it falls short, and a better implementation. A suggested order is at the end.

## Approaches to change

### 1. Two players + phase lock → one mixed stream

**Now.** Deck A and deck B are separate ExoPlayers with separate audio tracks. `PhaseController` nudges B's speed so B's reported position follows A's. Positions are estimates (interpolated, about ±10 ms jitter), and a speed change takes effect only after ~200–400 ms of buffered audio drains. That's why the loop needs rate limits, dead bands and preroll re-seeks, and why perfect lock is unreachable.

**Better.** Produce one stream in which B's samples are placed against A's by counting samples. With constant-tempo grids and a fixed speed ratio, alignment is then exact by construction, and there's no loop to tune. Two ways to get there:

| Option | How | For | Against |
| --- | --- | --- | --- |
| **A. Media3 `CompositionPlayer`** (transformer module) | A `Composition` with two audio sequences: A clipped to the zone end; B behind a gap so its entry lands on A's anchor, with a speed effect at the plan's ratio. Media3's `AudioMixer` sums them. `DeckAudioProcessor` is reused as each item's effect, counting frames from the clip start. | Sample-accurate, no phase lock, little new audio code. Ideal for the editor preview: two fixed songs and no media session. | `@RestrictTo(LIBRARY_GROUP)` and `@UnstableApi`. Usable here because the fork builds Media3 from the `media` submodule, but expect churn on upgrades. Presents a whole composition as one item, so a playlist needs a metadata/queue facade (see 2). Rebuilding the composition for each next pair has to happen without a gap. |
| **B. Own mixing processor in one player** | Deck A's processor pulls B's PCM from a background `MediaCodec` decoder, time-stretches it with a standalone Sonic at the fixed ratio, and mixes it in. | Full control; works with the existing single-player session. | The hard part is the hand-off after the zone: B must continue on its own player starting at the exact sample. That needs either a second short phase lock or keeping B inside A's pipeline until the song ends, which breaks the queue model. Weeks of device iteration. |

**Recommendation.** Prototype option A for the editor preview first. It's self-contained and gives a ground-truth version of each transition to compare against. Move playlists only together with item 2, and only if `MixDiagnostics` on real devices shows audible error (beyond ~0.03 beat). Moving the editor alone would bring back the preview/playlist mismatch Phase 2 removed, so either both move or neither does.

### 2. Logical state and deck swaps → one session-facing player

**Now.** `MixPlaybackEngine` publishes a `LogicalPlayerState` side channel for the in-app UI. The media session gets the raw active deck and is re-pointed on every swap and engine switch. The consequences:

- The notification and lock screen lag the in-app player by half a transition.
- `MusicService` has to filter events from the standby deck by comparing media IDs or playback state.
- Play history attributes listening time to the deck, not the song you heard.
- After each swap the queue is rebuilt onto the new deck (`QueueBoard.setCurrQueue`).
- Repeat mode, sleep timer and skip-silence have to be carried across players by hand.

**Better.** Give the session one stable `Player`: a `SimpleBasePlayer` facade ("MixSessionPlayer", a public Media3 API) whose state is the queue as the user sees it. Current item, position, duration and "playing" come from the engine. Commands (seek, skip, pause) go to the engine, which decides what they mean mid-transition. Then:

- The session, notification and in-app UI all read the same player, so `LogicalPlayerState` and its seek/position special cases go away.
- `mediaSession.player` is set once. The engine switch, deck swaps and the listener re-binding in `onActivePlayerChanged` disappear from `MusicService`.
- The UI can poll position from the player only while it's visible (see 3).
- It's also the piece a `CompositionPlayer` playlist (item 1) would need.

**Cost:** medium-large. It replaces the engine/session wiring in `MusicService`, but the engines underneath stay as they are.

### 3. Position pushed at 20 Hz → pulled while visible

**Now.** In mix mode the engine publishes a new `LogicalPlayerState` every 50 ms, and in normal mode every 500 ms, whenever music plays, even with the screen off. Each emission recomposes the player UI when it's open.

**Better.** Publish state only on discrete changes (item, duration, transition flag). Let the player screen read the position itself while it's visible, as upstream OuterTune did with `player.currentPosition` in a `LaunchedEffect`. This is battery and wakeups saved for the whole time music plays in the background. It falls out of item 2, or can be done alone in a few lines.

### 4. Volume owned in two places → the deck owns its gain

**Now.** `MusicService` applies user volume × normalization to whichever player is active. The mix engine sets deck volumes during a transition. The two coordinate through `isCrossfading` gating and song-tagged normalization factors, to avoid one briefly applying the other song's level.

**Better.** Apply loudness normalization inside `DeckAudioProcessor` as a constant per-item gain (it already sits in every mixing deck's chain and knows the item). Let the player volume carry only the user's volume. The race and the tagging then disappear. Normal playback can keep using player volume, since offload bypasses processors there.

### 5. Analysis

| Issue | Now | Better |
| --- | --- | --- |
| **Sample rate** | BTrack hard-codes 44.1 kHz (`BTrack.cpp:157, 280, 402, 462, 465`). YouTube Opus/WebM decodes at 48 kHz, so its tempo search runs about 9% off. Final BPM is re-measured from beat times, so it's right when the octave is right, but songs near the edges of BTrack's range (~80 BPM) can be detected at double or half tempo. | Resample to 44.1 kHz before BTrack (a simple polyphase or linear resampler is fine for onset detection), or pass the real rate into BTrack's period maths. |
| **Memory** | The whole song is held as mono floats (~42 MB for 4 minutes), and `GetFloatArrayElements` may copy it again on the native side. | BTrack is online (512-sample hops), and the waveform and bar detector only need short windows. Decode in chunks and feed all three as you go, for constant memory. |
| **Constant tempo** | `generateOptimizedGrid` forces one tempo for the whole song. Live drums, older recordings and tempo changes drift away from the grid, so the phase lock is fighting the grid rather than the players. | Keep BTrack's per-beat times, smooth them (median filter, or a tempo-curve fit), and store a variable grid. Mark songs whose tempo varies by more than ~1%, so the editor can warn. The engine already works on arbitrary grids. |
| **Streaming songs** | Only downloaded songs can be analysed and mixed. | Analyse from the player cache once a song has fully streamed, or decode from the stream URL in the worker. |
| **Key** | `KeyDetector` exists but is never called (see Phase 3 notes). | Wire it in the worker behind a try/catch at the JNI boundary, store Camelot notation, and show key compatibility in the editor. |

### 6. Smaller performance items

- **Playlist transition chips:** `LocalPlaylistViewModel.getTransitionState` opens one Room flow per adjacent pair. Every write to `transitions` re-runs all of them, and the cache map is never cleared. One query for the playlist's pairs (`WHERE fromSongId IN (...)`), mapped to a `Map<Pair, TransitionState>`, replaces N flows.
- **Queue reload after a swap:** `QueueBoard.setCurrQueue` builds `MediaItem`s for the whole queue on the main thread after every transition. For long queues, add only the next few items and extend as playback advances. With item 2 this goes away.
- **`DeckAudioProcessor`:** it processes one sample at a time through `ByteBuffer` getters in `Double`. That's fine at 88k samples/s, but bulk reads into a `ShortArray`/`FloatArray` with float maths would cut its cost by roughly 3–5x if profiling ever shows it.
- **Boxed grids:** grids are `List<Double>` (P11, still deferred). It only matters if grids get longer (variable-tempo grids, 5.3).
- **Mix poller:** it wakes every 50 ms while playing, even with no transition armed. It could sleep until `exitPoint − armLead` once armed or idle.

## What's solid and should stay

- `TransitionMath`, `MixController`, `PhaseController`, `DeckAutomation`, `CanonicalGrid`: pure, tested, and reusable by any of the designs above. Option A of item 1 would reuse `DeckAutomation` and `DeckAudioProcessor` unchanged.
- One canonical grid per song, built once and stored in binary.
- The persisted plan (`planVersion`) as the contract between editor and playback.
- `MixDiagnostics`: keep using it to decide whether item 1 is worth its cost.

## Suggested order

1. **Item 3** (pull position while visible): small, immediate battery win, no risk.
2. **5.1** (BTrack sample rate) and **6.1** (one query for the chips): small, isolated fixes.
3. **Item 4** (normalization inside the processor): removes the volume race.
4. **Item 2** (session-facing facade player): the structural fix. Unlocks 1 and simplifies `MusicService`.
5. Measure with `MixDiagnostics` on devices, then decide on **item 1** (prototype it in the editor preview first).
6. **5.2–5.5** as analysis work, independent of the playback track.
