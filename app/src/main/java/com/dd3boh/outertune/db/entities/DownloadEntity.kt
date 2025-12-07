package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class Download(
    @PrimaryKey
    val songId: String,  // References Song.id

    val localPath: String,  // Absolute file path starting with /

    val downloadedAt: Long = System.currentTimeMillis(),

    val fileSize: Long? = null
)
