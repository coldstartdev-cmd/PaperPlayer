package com.jp.paperplayer.ui.lyricseditor

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.model.ui.LyricsEditorPhase
import com.jp.paperplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsEditorScreen(
    playerViewModel: PlayerViewModel,
    editorViewModel: LyricsEditorViewModel,
    onNavigateBack: () -> Unit,
) {
    val editorState by editorViewModel.state.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val canUndo by editorViewModel.canUndo.collectAsStateWithLifecycle()
    val searchTitle by editorViewModel.searchTitle.collectAsStateWithLifecycle()
    val searchArtist by editorViewModel.searchArtist.collectAsStateWithLifecycle()
    val searchAlbum by editorViewModel.searchAlbum.collectAsStateWithLifecycle()
    val searchResults by editorViewModel.searchResults.collectAsStateWithLifecycle()
    val searchStatus by editorViewModel.searchStatus.collectAsStateWithLifecycle()
    val selectedTrack by editorViewModel.selectedTrack.collectAsStateWithLifecycle()
    val isPublishing by editorViewModel.isPublishing.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingRetry by remember { mutableStateOf<(() -> Unit)?>(null) }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingRetry?.invoke()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Write permission denied") }
        }
        pendingRetry = null
    }

    val onPermissionNeeded: (Uri, retry: () -> Unit) -> Unit = { uri, retry ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingRetry = retry
            try {
                val request = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                writePermissionLauncher.launch(IntentSenderRequest.Builder(request).build())
            } catch (e: Exception) {
                pendingRetry = null
                scope.launch { snackbarHostState.showSnackbar("Could not open permission dialog") }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Write access denied — grant storage permission in Settings")
            }
        }
    }

    BackHandler(
        enabled = editorState.phase == LyricsEditorPhase.SYNCING ||
                  editorState.phase == LyricsEditorPhase.FINETUNING
    ) {
        editorViewModel.backToEditing()
    }

    val currentSong = playerState.currentSong

    // LyricsEditorViewModel is a single Activity-scoped instance, so its state (raw text,
    // phase, search fields, etc.) would otherwise carry over from whichever song was last
    // edited. Reset it whenever the song being edited actually changes.
    LaunchedEffect(currentSong?.id) {
        editorViewModel.reset()
    }

    // Auto-load existing lyrics into the raw text box once they're available, instead of
    // requiring a button press. Lives here (alongside reset() above, in declaration order)
    // rather than in a child composable — Compose guarantees effects within one composable
    // run in source order, but does NOT guarantee a parent's effect finishes before a child's
    // when both are newly entering composition, which previously let this run before reset()
    // had cleared stale text from whichever song was edited last, silently skipping the load.
    // Keyed on song id too (not just the lyrics list) so switching between two songs that
    // both have existing lyrics still re-triggers the load.
    LaunchedEffect(currentSong?.id, playerState.lyrics) {
        if (playerState.lyrics.isNotEmpty() && editorViewModel.state.value.rawText.isBlank()) {
            editorViewModel.loadFromExisting(playerState.lyrics)
        }
    }

    var showVerifyDialog by remember { mutableStateOf(false) }
    var verifyTitle by remember { mutableStateOf("") }
    var verifyArtist by remember { mutableStateOf("") }
    var verifyAlbum by remember { mutableStateOf("") }

    fun doPublish(title: String, artist: String, album: String, durationMs: Long) {
        editorViewModel.publishToLrcLib(
            title       = title,
            artist      = artist,
            album       = album,
            durationSec = (durationMs / 1_000L).toInt(),
        ) { success ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (success) "Published to LRCLib!" else "Publish failed — check Logcat for details"
                )
            }
        }
    }

    // LRCLib's sync is trusted as-is — write it straight to the file instead of routing
    // through the fine-tuning screen for a manual review pass first.
    fun doUseTrackSynced() {
        val path = currentSong?.filePath
        val uri = currentSong?.uri
        if (path.isNullOrBlank() || uri == null) {
            scope.launch { snackbarHostState.showSnackbar("No song selected") }
            return
        }
        if (!editorViewModel.loadSelectedTrackSyncedLines()) {
            scope.launch { snackbarHostState.showSnackbar("No synced lyrics available") }
            return
        }
        editorViewModel.saveLyrics(path) { success, permDenied ->
            when {
                success -> {
                    playerViewModel.reloadCurrentLyrics()
                    scope.launch { snackbarHostState.showSnackbar("Synced lyrics saved!") }
                    onNavigateBack()
                }
                permDenied -> onPermissionNeeded(uri) {
                    editorViewModel.saveLyrics(path) { s, _ ->
                        if (s) {
                            playerViewModel.reloadCurrentLyrics()
                            scope.launch { snackbarHostState.showSnackbar("Synced lyrics saved!") }
                            onNavigateBack()
                        } else {
                            scope.launch { snackbarHostState.showSnackbar("Save failed") }
                        }
                    }
                }
                else -> scope.launch { snackbarHostState.showSnackbar("Save failed — check file permissions") }
            }
        }
    }

    // Always let the title/artist/album be reviewed and tweaked before publishing — duration
    // isn't part of this dialog since it's never user-edited, always read straight from the file.
    val onPublish: () -> Unit = {
        val song = currentSong
        if (song == null) {
            scope.launch { snackbarHostState.showSnackbar("No song selected") }
        } else {
            verifyTitle = song.title
            verifyArtist = song.artist
            verifyAlbum = song.album
            showVerifyDialog = true
        }
    }

    if (showVerifyDialog) {
        VerifyMetadataDialog(
            title = verifyTitle,
            artist = verifyArtist,
            album = verifyAlbum,
            onTitleChange = { verifyTitle = it },
            onArtistChange = { verifyArtist = it },
            onAlbumChange = { verifyAlbum = it },
            onDismiss = { showVerifyDialog = false },
            onConfirm = {
                showVerifyDialog = false
                currentSong?.let { doPublish(verifyTitle, verifyArtist, verifyAlbum, it.duration) }
            },
        )
    }

    when (editorState.phase) {
        LyricsEditorPhase.EDITING -> EditingPhase(
            editorState = editorState,
            hasExistingLyrics = playerState.lyrics.isNotEmpty(),
            songTitle = currentSong?.title,
            songArtist = currentSong?.artist,
            songDurationMs = currentSong?.duration,
            filePath = currentSong?.filePath,
            songUri = currentSong?.uri,
            snackbarHostState = snackbarHostState,
            searchTitle = searchTitle,
            searchArtist = searchArtist,
            searchAlbum = searchAlbum,
            searchResults = searchResults,
            searchStatus = searchStatus,
            selectedTrack = selectedTrack,
            onRawTextChange = editorViewModel::onRawTextChange,
            onSaveDirectly = editorViewModel::saveRawLyrics,
            onSaveSuccess = { playerViewModel.reloadCurrentLyrics() },
            onPermissionNeeded = onPermissionNeeded,
            onStartSyncing = {
                editorViewModel.startSyncing()
                // Only when this actually lands in stamp mode (currentIndex == 0) — if the
                // song already had synced lyrics it drops straight into review mode instead,
                // and playback shouldn't jump to zero in that case.
                if (editorViewModel.state.value.currentIndex == 0) {
                    playerViewModel.seekTo(0L)
                }
            },
            onStartFinetuning = { editorViewModel.startFinetuning(playerState.lyrics) },
            onNavigateBack = onNavigateBack,
            onInitSearchFields = editorViewModel::initSearchFields,
            onSearchTitleChange = editorViewModel::onSearchTitleChange,
            onSearchArtistChange = editorViewModel::onSearchArtistChange,
            onSearchAlbumChange = editorViewModel::onSearchAlbumChange,
            onSearch = { editorViewModel.searchLrcLib(((currentSong?.duration ?: 0L) / 1_000L).toInt()) },
            onSelectTrack = editorViewModel::selectTrack,
            onUseTrackSynced = { doUseTrackSynced() },
            onUseTrackAsPlain = editorViewModel::useTrackAsPlain,
            isPublishing = isPublishing,
            onPublish = onPublish,
        )
        LyricsEditorPhase.SYNCING -> SyncingPhase(
            editorState = editorState,
            playerState = playerState,
            filePath = currentSong?.filePath,
            songUri = currentSong?.uri,
            snackbarHostState = snackbarHostState,
            onStamp = { editorViewModel.stamp(playerState.positionMs) },
            onUndo = editorViewModel::undoLastStamp,
            onToggleMode = {
                val wasReviewMode = editorState.currentIndex >= editorState.lines.size
                editorViewModel.toggleSyncMode()
                if (wasReviewMode) playerViewModel.seekTo(0L)
            },
            onBeginReorder = editorViewModel::beginReorder,
            onReorder = editorViewModel::reorderLine,
            onDuplicate = editorViewModel::duplicateLine,
            onRemoveLine = editorViewModel::removeLine,
            onEditText = editorViewModel::editLineText,
            onBackToEditing = editorViewModel::backToEditing,
            onSave = editorViewModel::saveLyrics,
            onSaveSuccess = {
                playerViewModel.reloadCurrentLyrics()
                onNavigateBack()
            },
            onPermissionNeeded = onPermissionNeeded,
            onTogglePlayPause = playerViewModel::togglePlayPause,
            onSeek = playerViewModel::seekTo,
            onPause = playerViewModel::pause,
            onAdjust = editorViewModel::adjustTimestamp,
            onSetTimestamp = editorViewModel::setTimestamp,
            canUndoAdjust = canUndo,
            onUndoAdjust = editorViewModel::undoFinetune,
        )
        LyricsEditorPhase.FINETUNING -> FinetuningPhase(
            editorState = editorState,
            playerState = playerState,
            filePath = currentSong?.filePath,
            songUri = currentSong?.uri,
            snackbarHostState = snackbarHostState,
            onAdjust = editorViewModel::adjustTimestamp,
            onSetTimestamp = editorViewModel::setTimestamp,
            onEditText = editorViewModel::editLineText,
            onRemoveLine = editorViewModel::removeLine,
            onInsertLineAfter = editorViewModel::insertLineAfter,
            onBatchAdjust = editorViewModel::batchAdjust,
            onBackToEditing = editorViewModel::backToEditing,
            onSave = editorViewModel::saveLyrics,
            onSaveSuccess = {
                playerViewModel.reloadCurrentLyrics()
                onNavigateBack()
            },
            onPermissionNeeded = onPermissionNeeded,
            canUndo = canUndo,
            onUndo = editorViewModel::undoFinetune,
            onSkipPrevious = playerViewModel::skipPrevious,
            onSkipNext = playerViewModel::skipNext,
            onTogglePlayPause = playerViewModel::togglePlayPause,
            onSeek = playerViewModel::seekTo,
            onPause = playerViewModel::pause,
        )
    }
}

// Shown every time before publishing to LRCLib, so title/artist/album can be reviewed or
// corrected — entries are shared publicly, so bad metadata here propagates to everyone else
// who looks the song up. Duration isn't editable here; it's always read straight from the file.
@Composable
private fun VerifyMetadataDialog(
    title: String,
    artist: String,
    album: String,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish to LRCLib") },
        text = {
            Column {
                Text(
                    text = "Review these details before publishing — they'll be visible to " +
                        "everyone who looks this song up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = onArtistChange,
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = onAlbumChange,
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = title.isNotBlank() && artist.isNotBlank(),
            ) {
                Text("Publish")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
