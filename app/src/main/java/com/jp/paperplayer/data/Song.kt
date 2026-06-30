package com.jp.paperplayer.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: Uri,
    val filePath: String,
    val duration: Long,
    val albumArtUri: Uri?
)
