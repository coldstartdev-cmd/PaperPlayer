package com.jp.paperplayer.model.ui

import com.jp.paperplayer.model.data.LyricLine
import com.jp.paperplayer.model.data.Song

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentSong: Song? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyrics: List<LyricLine> = emptyList(),
    val displayLyrics: List<LyricLine> = emptyList(),
    val currentLyricIndex: Int = -1,
    val activeLanguage: String? = null,
    val availableLanguages: List<String> = emptyList(),
    val isTranslating: Boolean = false,
    val translationError: String? = null,
    val pendingEditorLanguage: String? = null,
    val pendingEditorLines: List<LyricLine> = emptyList(),
    val queue: List<Song> = emptyList(),
    val currentQueueIndex: Int = -1,
)
