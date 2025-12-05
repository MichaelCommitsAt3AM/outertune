    package com.dd3boh.outertune.playback

    import android.content.Context
    import android.util.Log
    import androidx.media3.common.MediaItem
    import androidx.media3.common.Player
    import androidx.media3.exoplayer.ExoPlayer
    import androidx.work.impl.SchedulersCreator
    import kotlinx.coroutines.*
    import kotlin.math.max

    /**
     * Manages two ExoPlayer instances (Deck A and Deck B) to enable seamless mixing.
     */
    class DeckManager(
        private val context: Context,
        private val playerCreator: () -> ExoPlayer
    ) {
        private val TAG = "DeckManager"

        val playerA: ExoPlayer = playerCreator()
        // Deck B only created when needed, saving resources
        private val playerB: ExoPlayer by lazy {
            Log.d(TAG, "Creating Deck B for DJ mode")
            playerCreator() }

        var activeDeck: ExoPlayer = playerA
            private set
        var standbyDeck: ExoPlayer = playerB
            private set

        private val mixerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var fadeJob: Job? = null

        fun prepareNext(mediaItem: MediaItem, startPositionMs: Long, bpmConfig: Float?) {
            Log.d(TAG, "Preparing Standby Deck: ${mediaItem.mediaMetadata.title}")

            standbyDeck.stop()
            standbyDeck.clearMediaItems()
            standbyDeck.setMediaItem(mediaItem)
            standbyDeck.seekTo(startPositionMs)
            standbyDeck.volume = 0f

            if (bpmConfig != null) {
                standbyDeck.setPlaybackSpeed(bpmConfig)
            }

            standbyDeck.prepare()
        }

        fun startCrossfade(durationMs: Long) {
            if (fadeJob?.isActive == true) return

            Log.i(TAG, "Starting Crossfade: $durationMs ms")

            val outgoingPlayer = activeDeck
            val incomingPlayer = standbyDeck

            incomingPlayer.play()

            fadeJob = mixerScope.launch {
                val startTime = System.currentTimeMillis()
                val endTime = startTime + durationMs

                while (isActive && System.currentTimeMillis() < endTime) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = elapsed.toFloat() / durationMs

                    outgoingPlayer.volume = max(0f, 1f - progress)
                    incomingPlayer.volume = max(0f, progress)

                    delay(16) // 60 FPS
                }

                completeTransition()
            }
        }

        private fun completeTransition() {
            Log.i(TAG, "Transition Complete")

            // Swap decks FIRST
            val temp = activeDeck
            activeDeck = standbyDeck
            standbyDeck = temp

            // Clean up old deck
            standbyDeck.stop()
            standbyDeck.clearMediaItems()
            standbyDeck.volume = 1f

            // Ensure active is full volume
            activeDeck.volume = 1f
        }

        fun cancelCrossfade() {
            fadeJob?.cancel()
            activeDeck.volume = 1f
            standbyDeck.volume = 0f
            standbyDeck.pause()
        }

        fun release() {
            mixerScope.cancel()
            playerA.release()
            playerB.release()
        }

        fun addListener(listener: Player.Listener) {
            playerA.addListener(listener)
            playerB.addListener(listener)
        }

        fun removeListener(listener: Player.Listener) {
            playerA.removeListener(listener)
            playerB.removeListener(listener)
        }
    }
