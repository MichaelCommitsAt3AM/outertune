package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dd3boh.outertune.db.entities.TransitionEntity

@Dao
interface TransitionDao {
    @Query("SELECT * FROM transitions WHERE fromSongId = :fromId AND toSongId = :toId")
    suspend fun getTransition(fromId: String, toId: String): TransitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transition: TransitionEntity)

    @Query("DELETE FROM transitions WHERE fromSongId = :fromId AND toSongId = :toId")
    suspend fun delete(fromId: String, toId: String)
}