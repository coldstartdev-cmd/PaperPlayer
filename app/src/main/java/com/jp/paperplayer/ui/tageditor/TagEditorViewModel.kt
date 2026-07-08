package com.jp.paperplayer.ui.tageditor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jp.paperplayer.lyrics.LrcParser
import com.jp.paperplayer.model.ui.TagEditorState
import com.jp.paperplayer.tagging.isPermissionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

class TagEditorViewModel : ViewModel() {

    private val _state = MutableStateFlow(TagEditorState())
    val state: StateFlow<TagEditorState> = _state.asStateFlow()

    fun load(filePath: String) {
        _state.value = TagEditorState(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = try {
                val tag = AudioFileIO.read(File(filePath)).tag
                val rawLyrics = tag?.getFirst(FieldKey.LYRICS) ?: ""
                val parsedLines = if (rawLyrics.isNotBlank()) LrcParser.parse(rawLyrics) else emptyList()
                // LrcParser falls back to untimed lines (timeMs == 0 on every line) when the
                // tag has no real LRC timestamps, so a non-empty result no longer implies synced.
                val isActuallySynced = parsedLines.isNotEmpty() && parsedLines.any { it.timeMs != 0L }
                TagEditorState(
                    title = tag?.getFirst(FieldKey.TITLE) ?: "",
                    artist = tag?.getFirst(FieldKey.ARTIST) ?: "",
                    album = tag?.getFirst(FieldKey.ALBUM) ?: "",
                    genre = tag?.getFirst(FieldKey.GENRE) ?: "",
                    year = tag?.getFirst(FieldKey.YEAR) ?: "",
                    trackNumber = tag?.getFirst(FieldKey.TRACK) ?: "",
                    language = tag?.getFirst(FieldKey.LANGUAGE) ?: "",
                    hasSyncedLyrics = isActuallySynced,
                    syncedLineCount = if (isActuallySynced) parsedLines.size else 0,
                    hasUnsyncedLyrics = rawLyrics.isNotBlank() && !isActuallySynced,
                    isLoading = false,
                )
            } catch (e: Exception) {
                Log.e("TagEditor", "Load failed", e)
                TagEditorState(isLoading = false)
            }
            withContext(Dispatchers.Main) { _state.value = loaded }
        }
    }

    fun updateTitle(value: String) { _state.value = _state.value.copy(title = value) }
    fun updateArtist(value: String) { _state.value = _state.value.copy(artist = value) }
    fun updateAlbum(value: String) { _state.value = _state.value.copy(album = value) }
    fun updateGenre(value: String) { _state.value = _state.value.copy(genre = value) }
    fun updateYear(value: String) { _state.value = _state.value.copy(year = value) }
    fun updateTrackNumber(value: String) { _state.value = _state.value.copy(trackNumber = value) }
    fun updateLanguage(value: String) { _state.value = _state.value.copy(language = value) }

    fun save(filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) {
        val fields = _state.value
        _state.value = fields.copy(isSaving = true)
        viewModelScope.launch(Dispatchers.IO) {
            var permissionDenied = false
            val success = try {
                val audioFile = AudioFileIO.read(File(filePath))
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.setField(FieldKey.TITLE, fields.title)
                tag.setField(FieldKey.ARTIST, fields.artist)
                tag.setField(FieldKey.ALBUM, fields.album)
                tag.setField(FieldKey.GENRE, fields.genre)
                tag.setField(FieldKey.YEAR, fields.year)
                tag.setField(FieldKey.TRACK, fields.trackNumber)
                tag.setField(FieldKey.LANGUAGE, fields.language)
                AudioFileIO.write(audioFile)
                true
            } catch (e: Exception) {
                Log.e("TagEditor", "Save failed", e)
                permissionDenied = isPermissionError(e)
                false
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(isSaving = false)
                onResult(success, permissionDenied)
            }
        }
    }
}
