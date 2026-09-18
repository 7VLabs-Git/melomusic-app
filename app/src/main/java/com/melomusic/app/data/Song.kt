package com.melomusic.app.data

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val durationText: String,
    val vlink: String = ""
)

data class LyricLine(
    val time: Float,
    val text: String
)