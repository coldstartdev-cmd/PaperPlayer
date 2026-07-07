package com.jp.paperplayer.model.ui

import com.jp.paperplayer.model.data.LyricLine

data class TranslationEditorState(
    val originalLines: List<LyricLine> = emptyList(),
    val editedLines: List<String> = emptyList(),
    val targetLanguage: String = "",
    val songId: Long = -1L,
    val listeningIndex: Int = -1,
    val ready: Boolean = false,
)
