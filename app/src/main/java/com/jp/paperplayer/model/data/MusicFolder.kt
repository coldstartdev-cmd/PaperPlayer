package com.jp.paperplayer.model.data

/** A folder on device storage that contains at least one scanned song. */
data class MusicFolder(
    val path: String,
    val name: String,
    val songCount: Int,
    val excluded: Boolean,
)
