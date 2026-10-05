package com.dd3boh.outertune.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MixSessionPlayerTest {

    /** An engine whose transition state the test sets directly. */
    private class FakeEngine(player: ExoPlayer) : PlaybackEngine {
        override val activePlayer = MutableStateFlow(player)
        override val isCrossfading = MutableStateFlow(false)
        override var onLogicalStateChanged: (() -> Unit)? = null
        var offset = 0
        override val logicalIndexOffset get() = offset
        var durationMs = 0L
        var positionMs = 0L
        val itemSeeks = mutableListOf<Int>()
        val positionSeeks = mutableListOf<Long>()

        override fun start() {}
        override fun destroy() {}
        override fun logicalPositionMs() = positionMs
        override fun logicalDurationMs() = durationMs
        override fun seekTo(positionMs: Long) { positionSeeks += positionMs }
        override fun seekToItem(index: Int, positionMs: Long) { itemSeeks += index }

        fun changed() = onLogicalStateChanged?.invoke()
    }

    private lateinit var deck: ExoPlayer
    private lateinit var engine: FakeEngine
    private lateinit var session: MixSessionPlayer
    private var userVolume = 0.8f

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("file:///$id.mp3").build()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        deck = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        // "a" twice: the same song may appear more than once in a queue.
        deck.setMediaItems(listOf(item("a"), item("b"), item("a"), item("c")))
        engine = FakeEngine(deck)
        session = MixSessionPlayer(userVolume = { userVolume }, onUserVolumeChange = { userVolume = it })
        session.bind(engine, deck)
        idle()
    }

    @After
    fun tearDown() {
        session.release()
        deck.release()
    }

    @Test
    fun `mirrors the active deck's queue, including repeated songs`() {
        assertEquals(4, session.mediaItemCount)
        assertEquals(0, session.currentMediaItemIndex)
        assertEquals("a", session.currentMediaItem?.mediaId)
        assertEquals("a", session.getMediaItemAt(2).mediaId)
    }

    @Test
    fun `from a transition's midpoint the next song is current`() {
        engine.offset = 1
        engine.durationMs = 200_000
        engine.positionMs = 1_500
        engine.changed()
        idle()

        assertEquals(1, session.currentMediaItemIndex)
        assertEquals("b", session.currentMediaItem?.mediaId)
        assertEquals(200_000, session.duration)
        assertEquals(1_500, session.currentPosition)
    }

    @Test
    fun `the current song's duration is its logical one`() {
        engine.durationMs = 150_000 // ends at its exit point
        engine.changed()
        idle()
        assertEquals(150_000, session.duration)
    }

    @Test
    fun `seeks go to the engine`() {
        session.seekTo(42_000)
        idle()
        assertEquals(listOf(0), engine.itemSeeks)

        session.seekToNextMediaItem()
        idle()
        assertEquals(1, engine.itemSeeks.last())
    }

    @Test
    fun `volume is the user's volume`() {
        assertEquals(0.8f, session.volume, 0f)
        session.volume = 0.3f
        idle()
        assertEquals(0.3f, userVolume, 0f)
        assertEquals(0.3f, session.volume, 0f)
    }

    @Test
    fun `an empty queue reports idle`() {
        deck.clearMediaItems()
        idle()
        assertEquals(0, session.mediaItemCount)
        assertEquals(Player.STATE_IDLE, session.playbackState)
    }

    @Test
    fun `follows a new deck after a swap`() {
        val other = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        try {
            other.setMediaItems(listOf(item("b"), item("c")))
            engine.activePlayer.value = other
            engine.changed()
            idle()
            assertEquals("b", session.currentMediaItem?.mediaId)
            assertEquals(2, session.mediaItemCount)
        } finally {
            other.release()
        }
    }
}
