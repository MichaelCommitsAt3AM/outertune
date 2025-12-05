package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "transitions",
    primaryKeys = ["fromSongId", "toSongId"],
    indices = [Index(value = ["fromSongId", "toSongId"], unique = true)]
)
data class TransitionEntity(
    val fromSongId: String,
    val toSongId: String,

    // The point in Song A (outgoing) where the crossfade starts
    val exitPointMs: Long,

    // The point in Song B (incoming) where playback begins
    val entryPointMs: Long,

    // How long the transition lasts (default 8s)
    val durationMs: Long = 8000,

    // The musical length (for the UI: 2, 4, 8, 16)
    val durationBeats: Int? = null,

    // Whether to force Deck B to match Deck A's BPM
    val syncTempo: Boolean = true,

    // Future-proofing: Manual vs Auto generated
    val type: Int = TYPE_MANUAL
) {
    companion object {
        const val TYPE_MANUAL = 0
        const val TYPE_AUTO = 1
    }
}