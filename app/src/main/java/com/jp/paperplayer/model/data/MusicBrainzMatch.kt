package com.jp.paperplayer.model.data

data class MusicBrainzMatch(
    val recordingId: String,
    val artistId: String?,
    val releaseId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val year: String?,
    val trackNumber: String?,
)
