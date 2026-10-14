// File: app/src/main/java/com/melomusic/app/data/local/SongDao.kt
package com.melomusic.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SongDao {
    @Query("SELECT * FROM cached_songs WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSong(song: SongEntity)

    // Strict era and language matching: old songs will NEVER enter modern queues
    @Query("""
        SELECT * FROM cached_songs 
        WHERE bpm BETWEEN :minBpm AND :maxBpm 
        AND language = :language
        AND ABS(CAST(CASE WHEN year = '' THEN '2020' ELSE year END AS INTEGER) - :seedYear) <= 12
        ORDER BY ABS(energyScore - :targetEnergy) ASC 
        LIMIT :limit
    """)
    suspend fun getSongsByAcousticProfile(
        minBpm: Int,
        maxBpm: Int,
        targetEnergy: Float,
        language: String,
        seedYear: Int,
        limit: Int = 15
    ): List<SongEntity>

    @Query("SELECT * FROM cached_songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<String>): List<SongEntity>
}