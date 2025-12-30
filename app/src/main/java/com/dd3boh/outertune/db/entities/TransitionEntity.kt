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

    // The point in Song B (incoming) where playback begins (aligns with start of crossfade)
    val entryPointMs: Long,

    // How long the transition lasts
    val durationMs: Long = 8000,
    val durationBeats: Int? = null,

    val syncTempo: Boolean = true,
    val type: Int = TYPE_MANUAL,

    // --- NEW FIELDS FOR EFFECT STORAGE ---
    val overlapMode: String = "Overlap", // Overlap, Crossfade, Cut
    val eqMode: String = "None",         // Bass Swaps etc
    val effectMode: String = "None"      // Filters etc
) {
    companion object {
        const val TYPE_MANUAL = 0
        const val TYPE_AUTO = 1
    }
}