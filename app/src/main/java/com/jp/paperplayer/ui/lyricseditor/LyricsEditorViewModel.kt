package com.jp.paperplayer.ui.lyricseditor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jp.paperplayer.lyrics.LrcLibClient
import com.jp.paperplayer.lyrics.LrcParser
import com.jp.paperplayer.lyrics.LrcWriter
import com.jp.paperplayer.model.data.LrcTrack
import com.jp.paperplayer.model.data.LyricLine
import com.jp.paperplayer.model.ui.EditorLine
import com.jp.paperplayer.model.ui.LyricsEditorPhase
import com.jp.paperplayer.model.ui.LyricsEditorState
import com.jp.paperplayer.model.ui.SearchStatus
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

class LyricsEditorViewModel : ViewModel() {

    private val _state = MutableStateFlow(LyricsEditorState())
    val state: StateFlow<LyricsEditorState> = _state.asStateFlow()

    val stampedCount get() = _state.value.lines.count { it.timeMs != null }

    private val undoStack = ArrayDeque<List<EditorLine>>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    // ── LRCLib search ─────────────────────────────────────────────────────────

    private val _searchTitle = MutableStateFlow("")
    val searchTitle: StateFlow<String> = _searchTitle.asStateFlow()

    // Optional narrowing fields, off by default — shown only behind a checkbox in the UI.
    private val _searchArtist = MutableStateFlow("")
    val searchArtist: StateFlow<String> = _searchArtist.asStateFlow()

    private val _searchAlbum = MutableStateFlow("")
    val searchAlbum: StateFlow<String> = _searchAlbum.asStateFlow()

    private val _searchResults = MutableStateFlow<List<LrcTrack>>(emptyList())
    val searchResults: StateFlow<List<LrcTrack>> = _searchResults.asStateFlow()

    private val _searchStatus = MutableStateFlow(SearchStatus.Idle)
    val searchStatus: StateFlow<SearchStatus> = _searchStatus.asStateFlow()

    private val _selectedTrack = MutableStateFlow<LrcTrack?>(null)
    val selectedTrack: StateFlow<LrcTrack?> = _selectedTrack.asStateFlow()

    private val _isPublishing = MutableStateFlow(false)
    val isPublishing: StateFlow<Boolean> = _isPublishing.asStateFlow()

    /**
     * Clears all editor state back to defaults. This ViewModel is a single Activity-scoped
     * instance reused across every song, so callers must invoke this whenever the song being
     * edited changes — otherwise raw text, phase, undo history, and search fields would leak
     * from whichever song was edited previously.
     */
    fun reset() {
        undoStack.clear()
        _canUndo.value = false
        _state.value = LyricsEditorState()
        _searchTitle.value = ""
        _searchArtist.value = ""
        _searchAlbum.value = ""
        _searchResults.value = emptyList()
        _searchStatus.value = SearchStatus.Idle
        _selectedTrack.value = null
        _isPublishing.value = false
    }

    fun initSearchFields(title: String) {
        if (_searchTitle.value.isBlank() && title.isNotBlank()) _searchTitle.value = title
    }

    fun onSearchTitleChange(value: String) { _searchTitle.value = value }

    fun onSearchArtistChange(value: String) { _searchArtist.value = value }

    fun onSearchAlbumChange(value: String) { _searchAlbum.value = value }

    /**
     * Looks up lyrics by title + duration, optionally narrowed by artist/album if those were
     * filled in: an exact-signature match from the internal database (fast), then one that lets
     * LRCLib fetch from external sources (slower), and only if neither finds anything, a keyword
     * search — falling back to searching each word of the title individually if the full title
     * doesn't match, since file-tag titles sometimes carry extra words/punctuation/annotations
     * that don't match LRCLib's stored title exactly.
     */
    fun searchLrcLib(durationSec: Int) {
        val title = _searchTitle.value.trim()
        if (title.isBlank()) return
        val artist = _searchArtist.value.trim().ifBlank { null }
        val album = _searchAlbum.value.trim().ifBlank { null }

        _searchStatus.value = SearchStatus.Loading
        _searchResults.value = emptyList()
        _selectedTrack.value = null
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) { fetchBestMatch(title, durationSec, artist, album) }
            _searchResults.value = results
            _searchStatus.value = if (results.isEmpty()) SearchStatus.NoResults else SearchStatus.Done
        }
    }

    private fun fetchBestMatch(title: String, durationSec: Int, artist: String?, album: String?): List<LrcTrack> {
        if (durationSec > 0) {
            LrcLibClient.getCached(title, durationSec)?.let { return listOf(it) }
            LrcLibClient.get(title, durationSec)?.let { return listOf(it) }
        }

        LrcLibClient.search(title, artist, album).let { if (it.isNotEmpty()) return it }

        val titleWords = title.split(Regex("\\s+")).filter { it.length >= 2 }
        if (titleWords.size >= 2) {
            for (word in titleWords) {
                val results = LrcLibClient.search(word, artist, album)
                if (results.isNotEmpty()) return results
            }
        }
        return emptyList()
    }

    fun selectTrack(track: LrcTrack) {
        _selectedTrack.value = if (_selectedTrack.value?.id == track.id) null else track
    }

    private val timestampPrefixRegex = Regex("""^\[\d+:\d+[.:]\d+]""")

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

    /** Loads the selected track's synced LRC into `lines`, ready to write straight to the
     *  file — LRCLib-provided sync is trusted as-is, so this skips the fine-tuning screen
     *  entirely. Returns false if there's no selected track or its LRC parses to nothing. */
    fun loadSelectedTrackSyncedLines(): Boolean {
        val lrc = _selectedTrack.value?.syncedLyrics ?: return false
        val editorLines = LrcParser.parse(lrc).map { EditorLine(it.text, it.timeMs) }
        if (editorLines.isEmpty()) return false
        _state.value = _state.value.copy(lines = editorLines)
        _selectedTrack.value = null
        return true
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

    /** Loads existing lyrics into the raw text box as-is. Plain (untimed) lyrics — i.e.
     *  every line came back with timeMs == 0 from LrcParser — are shown as plain text;
     *  actually LRC-timed lyrics keep their [mm:ss.xx] prefixes so re-editing preserves sync.
     *  Also pre-populates `lines` when already synced, so the Publish button can show (and
     *  actually work) immediately, without requiring a sync/fine-tune pass in this session. */
    fun loadFromExisting(existingLines: List<LyricLine>) {
        val isPlain = existingLines.isNotEmpty() && existingLines.all { it.timeMs == 0L }
        val text = if (isPlain) existingLines.joinToString("\n") { it.text }
                   else LrcWriter.write(existingLines)
        _state.value = _state.value.copy(
            rawText = text,
            lines = if (isPlain) _state.value.lines else existingLines.map { EditorLine(it.text, it.timeMs) },
        )
    }

    fun loadFromPlainText(text: String) {
        _state.value = _state.value.copy(phase = LyricsEditorPhase.EDITING, rawText = text)
    }

    // If the raw text box is already showing LRC-timed lyrics (e.g. loaded from an existing
    // file via loadFromExisting), skip the tap-along pass entirely and drop straight into
    // review mode with those timestamps pre-filled. Otherwise start a fresh stamping pass.
    fun startSyncing() {
        val rawText = _state.value.rawText
        val nonEmptyLines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val alreadyTimed = nonEmptyLines.isNotEmpty() && nonEmptyLines.all { timestampPrefixRegex.containsMatchIn(it) }

        undoStack.clear()
        _canUndo.value = false

        if (alreadyTimed) {
            val editorLines = LrcParser.parse(rawText).map { EditorLine(it.text, it.timeMs) }
            if (editorLines.isEmpty()) return
            _state.value = _state.value.copy(
                phase = LyricsEditorPhase.SYNCING,
                lines = editorLines,
                currentIndex = editorLines.size,
            )
        } else {
            val lines = nonEmptyLines.map { EditorLine(it) }
            if (lines.isEmpty()) return
            _state.value = _state.value.copy(
                phase = LyricsEditorPhase.SYNCING,
                lines = lines,
                currentIndex = 0,
            )
        }
    }

    /** Flips between stamp mode (currentIndex = 0) and review mode (currentIndex = lines.size)
     *  without touching any existing timestamps — nothing is discarded, so toggling back into
     *  stamp mode lets you redo taps while still seeing the old values for lines you haven't
     *  re-stamped yet in this pass. */
    fun toggleSyncMode() {
        val s = _state.value
        val allStamped = s.currentIndex >= s.lines.size
        _state.value = s.copy(currentIndex = if (allStamped) 0 else s.lines.size)
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
        val lines = _state.value.lines
        // Only write [mm:ss.xx] prefixes back once at least one line actually has a timestamp —
        // otherwise (backing out before stamping anything) this would incorrectly claim every
        // line starts at 00:00.00 instead of leaving the text plain.
        val text = if (lines.any { it.timeMs != null }) {
            LrcWriter.write(lines.map { LyricLine(it.timeMs ?: 0L, it.text) })
        } else {
            lines.joinToString("\n") { it.text }
        }
        _state.value = _state.value.copy(phase = LyricsEditorPhase.EDITING, rawText = text)
    }

    fun startFinetuning(existingLines: List<LyricLine>) {
        undoStack.clear()
        _canUndo.value = false
        val editorLines = existingLines.map { EditorLine(it.text, it.timeMs) }
        if (editorLines.isEmpty()) return
        _state.value = _state.value.copy(
            phase = LyricsEditorPhase.FINETUNING,
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

    // Duplicates a line right after itself, as an exact copy (same text, same timestamp) —
    // the user repositions and re-times the copy afterward via drag-to-reorder / the timestamp
    // editor, rather than this guessing a staggered time for it.
    fun duplicateLine(index: Int) {
        pushUndo()
        val s = _state.value
        val line = s.lines.getOrNull(index) ?: return
        // currentIndex doubles as a "fully stamped" sentinel in the Sync screen
        // (allStamped = currentIndex >= lines.size) — growing lines.size without keeping it in
        // step would make an already-fully-stamped list look unstamped again, bouncing the UI
        // back into stamping mode.
        val wasAllStamped = s.currentIndex >= s.lines.size
        val updated = s.lines.toMutableList().also { it.add(index + 1, line.copy()) }
        _state.value = s.copy(
            lines = updated,
            currentIndex = if (wasAllStamped) updated.size else s.currentIndex,
        )
    }

    /** Pushes one undo snapshot before a drag-to-reorder gesture begins, so the whole
     *  gesture (which may swap several times) undoes as a single step. */
    fun beginReorder() = pushUndo()

    fun reorderLine(fromIndex: Int, toIndex: Int) {
        val s = _state.value
        if (fromIndex !in s.lines.indices || toIndex !in s.lines.indices) return
        _state.value = s.copy(
            lines = s.lines.toMutableList().also { it.add(toIndex, it.removeAt(fromIndex)) }
        )
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
