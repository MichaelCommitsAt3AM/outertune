package com.dd3boh.outertune.db.entities


import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class Download(
    @PrimaryKey
    val songId: String,  // References Song.id

    val localPath: String,  // Absolute file path starting with /

    val downloadedAt: Long = System.currentTimeMillis(),

    val fileSize: Long? = null,

    // Analysis results
    val bpm: Float? = null,
    val beatGrid: String? = null,  // JSON or comma-separated
    val waveformData: String? = null,  // Amplitude data

    val analysisStatus: AnalysisStatus = AnalysisStatus.PENDING,
    val analyzedAt: Long? = null
)

enum class AnalysisStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
