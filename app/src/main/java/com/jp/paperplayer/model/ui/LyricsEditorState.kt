package com.jp.paperplayer.model.ui

data class EditorLine(val text: String, val timeMs: Long? = null)

enum class LyricsEditorPhase { EDITING, SYNCING, FINETUNING }

enum class SearchStatus { Idle, Loading, Done, NoResults }

data class LyricsEditorState(
    val phase: LyricsEditorPhase = LyricsEditorPhase.EDITING,
    val rawText: String = "",
    val lines: List<EditorLine> = emptyList(),
    val currentIndex: Int = 0,
    val isSaving: Boolean = false,
)
