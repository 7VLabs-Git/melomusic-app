// File: app/src/main/java/com/melomusic/app/data/local/SongEntity.kt
package com.melomusic.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.melomusic.app.data.Song

@Entity(tableName = "cached_songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val durationText: String,
    val encryptedMediaUrl: String,
    val localPath: String? = null,
    val streamUrl320: String? = null,
    val streamUrl160: String? = null,
    val bpm: Int = 120,
    val energyScore: Float = 0.5f,
    val year: String = "",
    val language: String = "hindi",
    val lastAccessedTimestamp: Long = System.currentTimeMillis()
) {
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        encryptedMediaUrl = encryptedMediaUrl,
        localPath = localPath,
        year = year,
        language = language
    )
}