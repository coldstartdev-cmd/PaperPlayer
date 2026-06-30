package com.jp.paperplayer.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jp.paperplayer.data.LyricLine
import com.jp.paperplayer.lyrics.LrcLibClient
import com.jp.paperplayer.lyrics.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.NoWritePermissionsException
import org.jaudiotagger.tag.FieldKey
import java.io.File

class LyricsEditorViewModel : ViewModel() {

    data class EditorLine(val text: String, val timeMs: Long? = null)

    enum class Phase { EDITING, SYNCING, FINETUNING }

    data class EditorState(
        val phase: Phase = Phase.EDITING,
        val rawText: String = "",
        val lines: List<EditorLine> = emptyList(),
        val currentIndex: Int = 0,
        val isSaving: Boolean = false,
    )

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val stampedCount get() = _state.value.lines.count { it.timeMs != null }

    private val undoStack = ArrayDeque<List<EditorLine>>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    // ── LRCLib search ─────────────────────────────────────────────────────────

    enum class SearchStatus { Idle, Loading, Done, NoResults }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<LrcLibClient.Track>>(emptyList())
    val searchResults: StateFlow<List<LrcLibClient.Track>> = _searchResults.asStateFlow()

    private val _searchStatus = MutableStateFlow(SearchStatus.Idle)
    val searchStatus: StateFlow<SearchStatus> = _searchStatus.asStateFlow()

    private val _selectedTrack = MutableStateFlow<LrcLibClient.Track?>(null)
    val selectedTrack: StateFlow<LrcLibClient.Track?> = _selectedTrack.asStateFlow()

    private val _isPublishing = MutableStateFlow(false)
    val isPublishing: StateFlow<Boolean> = _isPublishing.asStateFlow()

    fun initSearchQuery(title: String, artist: String) {
        if (_searchQuery.value.isBlank() && title.isNotBlank()) {
            _searchQuery.value = "$title $artist".trim()
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun searchLrcLib() {
        val q = _searchQuery.value.trim()
        if (q.isBlank()) return
        _searchStatus.value = SearchStatus.Loading
        _searchResults.value = emptyList()
        _selectedTrack.value = null
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) { LrcLibClient.search(q) }
            _searchResults.value = results
            _searchStatus.value = if (results.isEmpty()) SearchStatus.NoResults else SearchStatus.Done
        }
    }

    fun selectTrack(track: LrcLibClient.Track) {
        _selectedTrack.value = if (_selectedTrack.value?.id == track.id) null else track
    }

    /** Strips LRC timestamps from a synced lyrics string, returning plain line text. */
    private fun stripTimestamps(lrc: String): String {
        val tsRegex = Regex("""^\[\d+:\d+[.:]\d+]\s*""")
        return lrc.lines()
            .map { it.replace(tsRegex, "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** Loads plain text into the editor (editing phase). */
    fun useTrackAsPlain() {
        val track = _selectedTrack.value ?: return
        val plain = track.plainLyrics
            ?: track.syncedLyrics?.let { stripTimestamps(it) }
            ?: return
        loadFromPlainText(plain)
        _selectedTrack.value = null
    }

    /** Parses the track's synced LRC and jumps straight to fine-tuning. */
    fun useTrackSynced() {
        val lrc = _selectedTrack.value?.syncedLyrics ?: return
        startFinetuningFromLrc(lrc)
        _selectedTrack.value = null
    }

    fun startFinetuningFromLrc(lrcText: String) {
        val parsed = LrcParser.parse(lrcText)
        val editorLines = parsed.map { EditorLine(it.text, it.timeMs) }
        if (editorLines.isEmpty()) return
        undoStack.clear()
        _canUndo.value = false
        _state.value = _state.value.copy(phase = Phase.FINETUNING, lines = editorLines)
    }

    // ── LRCLib publish ────────────────────────────────────────────────────────

    fun publishToLrcLib(
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        onResult: (success: Boolean) -> Unit,
    ) {
        val syncedLrc = buildLrc()
        if (syncedLrc.isBlank()) {
            onResult(false)
            return
        }
        val plainText = _state.value.lines.joinToString("\n") { it.text }
        _isPublishing.value = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                LrcLibClient.publish(
                    trackName    = title,
                    artistName   = artist,
                    albumName    = album,
                    durationSec  = durationSec,
                    plainLyrics  = plainText,
                    syncedLyrics = syncedLrc,
                )
            }
            _isPublishing.value = false
            onResult(success)
        }
    }

    private fun pushUndo() {
        undoStack.addLast(_state.value.lines)
        if (undoStack.size > 50) undoStack.removeFirst()
        _canUndo.value = true
    }

    fun undoFinetune() {
        val prev = undoStack.removeLastOrNull() ?: return
        _state.value = _state.value.copy(lines = prev)
        _canUndo.value = undoStack.isNotEmpty()
    }

    fun onRawTextChange(text: String) {
        _state.value = _state.value.copy(rawText = text)
    }

    fun loadFromExisting(existingLines: List<LyricLine>) {
        _state.value = _state.value.copy(
            rawText = existingLines.joinToString("\n") { it.text }
        )
    }

    fun loadFromPlainText(text: String) {
        _state.value = _state.value.copy(phase = Phase.EDITING, rawText = text)
    }

    fun startSyncing() {
        val lines = _state.value.rawText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { EditorLine(it) }
        if (lines.isEmpty()) return
        _state.value = _state.value.copy(
            phase = Phase.SYNCING,
            lines = lines,
            currentIndex = 0,
        )
    }

    fun stamp(positionMs: Long) {
        val s = _state.value
        val idx = s.currentIndex
        if (idx >= s.lines.size) return
        val updated = s.lines.toMutableList().also { it[idx] = it[idx].copy(timeMs = positionMs) }
        _state.value = s.copy(
            lines = updated,
            currentIndex = (idx + 1).coerceAtMost(s.lines.size),
        )
    }

    fun undoLastStamp() {
        val s = _state.value
        if (s.currentIndex == 0) return
        val idx = s.currentIndex - 1
        val updated = s.lines.toMutableList().also { it[idx] = it[idx].copy(timeMs = null) }
        _state.value = s.copy(lines = updated, currentIndex = idx)
    }

    fun backToEditing() {
        _state.value = _state.value.copy(
            phase = Phase.EDITING,
            rawText = _state.value.lines.joinToString("\n") { it.text },
        )
    }

    fun startFinetuning(existingLines: List<LyricLine>) {
        undoStack.clear()
        _canUndo.value = false
        val editorLines = existingLines.map { EditorLine(it.text, it.timeMs) }
        if (editorLines.isEmpty()) return
        _state.value = _state.value.copy(
            phase = Phase.FINETUNING,
            lines = editorLines,
        )
    }

    fun adjustTimestamp(index: Int, deltaMs: Long) {
        val s = _state.value
        if (index !in s.lines.indices) return
        pushUndo()
        val newTime = ((s.lines[index].timeMs ?: 0L) + deltaMs).coerceAtLeast(0L)
        _state.value = s.copy(
            lines = s.lines.toMutableList().also { it[index] = it[index].copy(timeMs = newTime) }
        )
    }

    fun setTimestamp(index: Int, timeMs: Long) {
        val s = _state.value
        if (index !in s.lines.indices) return
        _state.value = s.copy(
            lines = s.lines.toMutableList().also { it[index] = it[index].copy(timeMs = timeMs.coerceAtLeast(0L)) }
        )
    }

    fun editLineText(index: Int, text: String) {
        val s = _state.value
        if (index !in s.lines.indices) return
        _state.value = s.copy(
            lines = s.lines.toMutableList().also { it[index] = it[index].copy(text = text) }
        )
    }

    fun removeLine(index: Int) {
        val s = _state.value
        if (index !in s.lines.indices) return
        pushUndo()
        _state.value = s.copy(lines = s.lines.toMutableList().also { it.removeAt(index) })
    }

    // Inserts a blank line after `index` with a timestamp midway between the current and next line.
    fun insertLineAfter(index: Int) {
        pushUndo()
        val s = _state.value
        val currentMs = s.lines.getOrNull(index)?.timeMs ?: 0L
        val nextMs = s.lines.getOrNull(index + 1)?.timeMs
        val insertMs = if (nextMs != null) (currentMs + nextMs) / 2 else currentMs + 2_000L
        val updated = s.lines.toMutableList().also { it.add(index + 1, EditorLine("", insertMs)) }
        _state.value = s.copy(lines = updated)
    }

    fun batchAdjust(indices: Set<Int>, deltaMs: Long) {
        if (indices.isEmpty()) return
        pushUndo()
        val s = _state.value
        _state.value = s.copy(
            lines = s.lines.mapIndexed { i, line ->
                if (i in indices) line.copy(timeMs = ((line.timeMs ?: 0L) + deltaMs).coerceAtLeast(0L))
                else line
            }
        )
    }

    fun saveRawLyrics(filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) {
        _state.value = _state.value.copy(isSaving = true)
        viewModelScope.launch(Dispatchers.IO) {
            var permissionDenied = false
            val success = try {
                val audioFile = AudioFileIO.read(File(filePath))
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.setField(FieldKey.LYRICS, _state.value.rawText.trim())
                AudioFileIO.write(audioFile)
                true
            } catch (e: Exception) {
                Log.e("LyricsEditor", "Save (raw) failed", e)
                permissionDenied = isPermissionError(e)
                false
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(isSaving = false)
                onResult(success, permissionDenied)
            }
        }
    }

    fun saveLyrics(filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) {
        _state.value = _state.value.copy(isSaving = true)
        viewModelScope.launch(Dispatchers.IO) {
            var permissionDenied = false
            val success = try {
                val lrc = buildLrc()
                val audioFile = AudioFileIO.read(File(filePath))
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.setField(FieldKey.LYRICS, lrc)
                AudioFileIO.write(audioFile)
                true
            } catch (e: Exception) {
                Log.e("LyricsEditor", "Save failed", e)
                permissionDenied = isPermissionError(e)
                false
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(isSaving = false)
                onResult(success, permissionDenied)
            }
        }
    }

    // JAudioTagger wraps OS errors in its own exception types, so we walk the full
    // cause chain and also check the top-level message string (which JAudioTagger
    // builds by embedding the cause's toString()).
    private fun isPermissionError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SecurityException) return true
            if (t is NoWritePermissionsException) return true
            val msg = t.message ?: ""
            if (msg.contains("EACCES") ||
                msg.contains("Permission denied", ignoreCase = true) ||
                msg.contains("do not have permissions", ignoreCase = true) ||
                msg.contains("permissions to modify", ignoreCase = true)
            ) return true
            t = t.cause
        }
        return false
    }

    private fun buildLrc(): String {
        return _state.value.lines
            .filter { it.timeMs != null }
            .sortedBy { it.timeMs }
            .joinToString("\n") { line ->
                val ms = line.timeMs!!
                val min = ms / 60_000
                val sec = (ms % 60_000) / 1_000
                val cs = (ms % 1_000) / 10
                "[%02d:%02d.%02d]%s".format(min, sec, cs, line.text)
            }
    }
}
