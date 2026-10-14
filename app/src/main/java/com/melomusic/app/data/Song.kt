package com.melomusic.app.data

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationText: String = "0:00",
    val thumbnailUrl: String = "",
    val mediaUrl: String? = null,
    val encryptedMediaUrl: String? = null,
    var localPath: String? = null
)

data class LyricLine(
    val time: Float,
    val text: String
)