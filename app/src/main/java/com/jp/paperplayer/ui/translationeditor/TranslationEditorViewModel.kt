package com.jp.paperplayer.ui.translationeditor

import androidx.lifecycle.ViewModel
import com.jp.paperplayer.model.data.LyricLine
import com.jp.paperplayer.model.ui.TranslationEditorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TranslationEditorViewModel : ViewModel() {

    private val _state = MutableStateFlow(TranslationEditorState())
    val state: StateFlow<TranslationEditorState> = _state.asStateFlow()

    fun setup(
        originalLines: List<LyricLine>,
        translatedLines: List<LyricLine>,
        targetLanguage: String,
        songId: Long,
    ) {
        _state.value = TranslationEditorState(
            originalLines = originalLines,
            editedLines = translatedLines.map { it.text },
            targetLanguage = targetLanguage,
            songId = songId,
            ready = true,
        )
    }

    fun updateLine(index: Int, text: String) {
        val s = _state.value
        if (index !in s.editedLines.indices) return
        _state.value = s.copy(
            editedLines = s.editedLines.toMutableList().also { it[index] = text }
        )
    }

    fun setListeningIndex(index: Int) {
        _state.value = _state.value.copy(listeningIndex = index)
    }

    fun toSavableLines(): List<LyricLine> {
        val s = _state.value
        return s.originalLines.zip(s.editedLines) { original, text -> original.copy(text = text) }
    }
}
