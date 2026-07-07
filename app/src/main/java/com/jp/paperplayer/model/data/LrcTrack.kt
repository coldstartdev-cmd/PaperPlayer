package com.jp.paperplayer.model.data

data class LrcTrack(
    val id: Int,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val duration: Int,          // seconds
    val syncedLyrics: String?,
    val plainLyrics: String?,
)
