package com.jp.paperplayer.model.ui

data class TagEditorState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val language: String = "",
    val hasSyncedLyrics: Boolean = false,
    val syncedLineCount: Int = 0,
    val hasUnsyncedLyrics: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val musicBrainzTrackId: String? = null,
    val musicBrainzArtistId: String? = null,
    val musicBrainzReleaseId: String? = null,
)
