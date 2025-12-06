package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.dd3boh.outertune.db.entities.AnalysisStatus
import com.dd3boh.outertune.db.entities.Download
import com.dd3boh.outertune.db.entities.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: Download)

    @Query("SELECT * FROM downloads WHERE songId = :songId")
    suspend fun getDownload(songId: String): Download?

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun deleteDownload(songId: String)

    @Query("SELECT * FROM downloads WHERE analysisStatus = :status")
    suspend fun getDownloadsByAnalysisStatus(status: AnalysisStatus): List<Download>

    @Query("UPDATE downloads SET analysisStatus = :status WHERE songId = :songId")
    suspend fun updateAnalysisStatus(songId: String, status: AnalysisStatus)

    @Transaction
    @Query("SELECT * FROM song WHERE id IN (SELECT songId FROM downloads)")
    fun getDownloadedSongsWithInfo(): Flow<List<SongWithDownload>>
}

// Relationship model
data class SongWithDownload(
    @Embedded val song: Song,
    @Relation(
        parentColumn = "id",
        entityColumn = "songId"
    )
    val download: Download?
)
