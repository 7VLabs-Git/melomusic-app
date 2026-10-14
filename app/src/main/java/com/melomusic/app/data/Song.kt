// File: app/src/main/java/com/melomusic/app/data/Song.kt
package com.melomusic.app.data

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val thumbnailUrl: String = "",
    val durationText: String = "3:30",
    val encryptedMediaUrl: String? = "",
    var localPath: String? = null,
    val year: String = "",
    val language: String = "hindi",
    val mediaUrl: String? = null
)

data class LyricLine(
    val time: Float,
    val text: String
)