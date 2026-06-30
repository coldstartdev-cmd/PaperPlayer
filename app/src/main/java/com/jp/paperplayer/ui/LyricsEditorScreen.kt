package com.jp.paperplayer.ui

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.lyrics.LrcLibClient
import com.jp.paperplayer.viewmodel.LyricsEditorViewModel
import com.jp.paperplayer.viewmodel.PlayerState
import com.jp.paperplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
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
    val searchQuery by editorViewModel.searchQuery.collectAsStateWithLifecycle()
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
        enabled = editorState.phase == LyricsEditorViewModel.Phase.SYNCING ||
                  editorState.phase == LyricsEditorViewModel.Phase.FINETUNING
    ) {
        editorViewModel.backToEditing()
    }

    val currentSong = playerState.currentSong

    val onPublish: () -> Unit = {
        val song = currentSong
        if (song == null) {
            scope.launch { snackbarHostState.showSnackbar("No song selected") }
        } else {
            editorViewModel.publishToLrcLib(
                title       = song.title,
                artist      = song.artist,
                album       = song.album,
                durationSec = (song.duration / 1_000L).toInt(),
            ) { success ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (success) "Published to LRCLib!" else "Publish failed — check Logcat for details"
                    )
                }
            }
        }
    }

    when (editorState.phase) {
        LyricsEditorViewModel.Phase.EDITING -> EditingPhase(
            editorState = editorState,
            hasExistingLyrics = playerState.lyrics.isNotEmpty(),
            songTitle = currentSong?.title,
            songArtist = currentSong?.artist,
            filePath = currentSong?.filePath,
            songUri = currentSong?.uri,
            snackbarHostState = snackbarHostState,
            searchQuery = searchQuery,
            searchResults = searchResults,
            searchStatus = searchStatus,
            selectedTrack = selectedTrack,
            onRawTextChange = editorViewModel::onRawTextChange,
            onLoadExisting = { editorViewModel.loadFromExisting(playerState.lyrics) },
            onSaveDirectly = editorViewModel::saveRawLyrics,
            onSaveSuccess = { playerViewModel.reloadCurrentLyrics() },
            onPermissionNeeded = onPermissionNeeded,
            onStartSyncing = editorViewModel::startSyncing,
            onStartFinetuning = { editorViewModel.startFinetuning(playerState.lyrics) },
            onNavigateBack = onNavigateBack,
            onInitSearchQuery = editorViewModel::initSearchQuery,
            onSearchQueryChange = editorViewModel::onSearchQueryChange,
            onSearch = editorViewModel::searchLrcLib,
            onSelectTrack = editorViewModel::selectTrack,
            onUseTrackSynced = editorViewModel::useTrackSynced,
            onUseTrackAsPlain = editorViewModel::useTrackAsPlain,
        )
        LyricsEditorViewModel.Phase.SYNCING -> SyncingPhase(
            editorState = editorState,
            playerState = playerState,
            playerViewModel = playerViewModel,
            filePath = currentSong?.filePath,
            songUri = currentSong?.uri,
            snackbarHostState = snackbarHostState,
            isPublishing = isPublishing,
            onStamp = { editorViewModel.stamp(playerState.positionMs) },
            onUndo = editorViewModel::undoLastStamp,
            onBackToEditing = editorViewModel::backToEditing,
            onSave = editorViewModel::saveLyrics,
            onSaveSuccess = {
                playerViewModel.reloadCurrentLyrics()
                onNavigateBack()
            },
            onPermissionNeeded = onPermissionNeeded,
            onPublish = onPublish,
        )
        LyricsEditorViewModel.Phase.FINETUNING -> FinetuningPhase(
            editorState = editorState,
            playerState = playerState,
            playerViewModel = playerViewModel,
            filePath = currentSong?.filePath,
            songUri = currentSong?.uri,
            snackbarHostState = snackbarHostState,
            isPublishing = isPublishing,
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
            onPublish = onPublish,
        )
    }
}

// ── Phase 1: type / paste all lyric lines ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditingPhase(
    editorState: LyricsEditorViewModel.EditorState,
    hasExistingLyrics: Boolean,
    songTitle: String?,
    songArtist: String?,
    filePath: String?,
    songUri: Uri?,
    snackbarHostState: SnackbarHostState,
    searchQuery: String,
    searchResults: List<LrcLibClient.Track>,
    searchStatus: LyricsEditorViewModel.SearchStatus,
    selectedTrack: LrcLibClient.Track?,
    onRawTextChange: (String) -> Unit,
    onLoadExisting: () -> Unit,
    onSaveDirectly: (filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) -> Unit,
    onSaveSuccess: () -> Unit,
    onPermissionNeeded: (Uri, retry: () -> Unit) -> Unit,
    onStartSyncing: () -> Unit,
    onStartFinetuning: () -> Unit,
    onNavigateBack: () -> Unit,
    onInitSearchQuery: (title: String, artist: String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectTrack: (LrcLibClient.Track) -> Unit,
    onUseTrackSynced: () -> Unit,
    onUseTrackAsPlain: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(songTitle) {
        if (songTitle != null) onInitSearchQuery(songTitle, songArtist ?: "")
    }

    fun save(path: String, uri: Uri) {
        onSaveDirectly(path) { success, permDenied ->
            when {
                success -> {
                    onSaveSuccess()
                    scope.launch { snackbarHostState.showSnackbar("Lyrics saved!") }
                }
                permDenied -> onPermissionNeeded(uri) {
                    onSaveDirectly(path) { s, _ ->
                        scope.launch { snackbarHostState.showSnackbar(if (s) "Lyrics saved!" else "Save failed") }
                        if (s) onSaveSuccess()
                    }
                }
                else -> scope.launch {
                    snackbarHostState.showSnackbar("Save failed — check file permissions")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (songTitle != null) {
                        Column {
                            Text(songTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(songArtist ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    } else {
                        Text("Edit Lyrics")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedTab == 0) {
                        if (editorState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                        } else {
                            IconButton(
                                onClick = {
                                    val path = filePath
                                    val uri = songUri
                                    if (path.isNullOrBlank() || uri == null) {
                                        scope.launch { snackbarHostState.showSnackbar("No song selected") }
                                        return@IconButton
                                    }
                                    save(path, uri)
                                },
                                enabled = editorState.rawText.isNotBlank() && !filePath.isNullOrBlank(),
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = "Save lyrics")
                            }
                        }
                        TextButton(onClick = onStartSyncing, enabled = editorState.rawText.isNotBlank()) {
                            Text("Sync →")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Write") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Search LRCLib") },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }

            when (selectedTab) {
                0 -> WriteTab(
                    editorState = editorState,
                    hasExistingLyrics = hasExistingLyrics,
                    onRawTextChange = onRawTextChange,
                    onLoadExisting = onLoadExisting,
                    onStartFinetuning = onStartFinetuning,
                )
                1 -> SearchTab(
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    searchStatus = searchStatus,
                    selectedTrack = selectedTrack,
                    onQueryChange = onSearchQueryChange,
                    onSearch = onSearch,
                    onSelectTrack = onSelectTrack,
                    onUseTrackSynced = onUseTrackSynced,
                    onUseTrackAsPlain = onUseTrackAsPlain,
                )
            }
        }
    }
}

@Composable
private fun WriteTab(
    editorState: LyricsEditorViewModel.EditorState,
    hasExistingLyrics: Boolean,
    onRawTextChange: (String) -> Unit,
    onLoadExisting: () -> Unit,
    onStartFinetuning: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        if (hasExistingLyrics) {
            Row {
                OutlinedButton(onClick = onLoadExisting, modifier = Modifier.weight(1f)) {
                    Text("Load existing lyrics")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onStartFinetuning) {
                    Text("Fine-tune")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = editorState.rawText,
            onValueChange = onRawTextChange,
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text("Lyrics") },
            placeholder = {
                Text(
                    "One lyric line per line:\n\nVerse 1 line 1\nVerse 1 line 2\n\nFor pre-timed LRC paste directly:\n[01:23.45]Line text",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "${editorState.rawText.lines().count { it.isNotBlank() }} lines  ·  ✓ saves as-is  ·  Sync → adds timestamps",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchTab(
    searchQuery: String,
    searchResults: List<LrcLibClient.Track>,
    searchStatus: LyricsEditorViewModel.SearchStatus,
    selectedTrack: LrcLibClient.Track?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectTrack: (LrcLibClient.Track) -> Unit,
    onUseTrackSynced: () -> Unit,
    onUseTrackAsPlain: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {

        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Song title or artist") },
                trailingIcon = {
                    if (searchStatus == LyricsEditorViewModel.SearchStatus.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSearch, enabled = searchQuery.isNotBlank() && searchStatus != LyricsEditorViewModel.SearchStatus.Loading) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }

        when (searchStatus) {
            LyricsEditorViewModel.SearchStatus.NoResults -> {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No results found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                // Results list
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(searchResults) { _, track ->
                        SearchResultItem(
                            track = track,
                            isSelected = selectedTrack?.id == track.id,
                            onClick = { onSelectTrack(track) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (searchResults.isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }

                // Action panel for selected track
                if (selectedTrack != null) {
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = selectedTrack.trackName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${selectedTrack.artistName}  ·  ${selectedTrack.albumName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selectedTrack.syncedLyrics != null) {
                                FilledTonalButton(
                                    onClick = onUseTrackSynced,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Use with Timing", maxLines = 1)
                                }
                            }
                            OutlinedButton(
                                onClick = onUseTrackAsPlain,
                                modifier = Modifier.weight(1f),
                                enabled = selectedTrack.plainLyrics != null || selectedTrack.syncedLyrics != null,
                            ) {
                                Text("Use Plain Text", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    track: LrcLibClient.Track,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
             else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.trackName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artistName}  ·  ${track.albumName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val mins = track.duration / 60
            val secs = track.duration % 60
            Text(
                text = "%d:%02d".format(mins, secs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (track.syncedLyrics != null) {
                Text(
                    text = "synced",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ── Phase 2: play song and tap to stamp each line ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncingPhase(
    editorState: LyricsEditorViewModel.EditorState,
    playerState: PlayerState,
    playerViewModel: PlayerViewModel,
    filePath: String?,
    songUri: Uri?,
    snackbarHostState: SnackbarHostState,
    isPublishing: Boolean,
    onStamp: () -> Unit,
    onUndo: () -> Unit,
    onBackToEditing: () -> Unit,
    onSave: (filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) -> Unit,
    onSaveSuccess: () -> Unit,
    onPermissionNeeded: (Uri, retry: () -> Unit) -> Unit,
    onPublish: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lyricsListState = rememberLazyListState()
    val currentIndex = editorState.currentIndex
    val lines = editorState.lines
    val allStamped = currentIndex >= lines.size

    LaunchedEffect(currentIndex) {
        val target = currentIndex.coerceAtMost(lines.size - 1)
        if (target >= 0) lyricsListState.animateScrollToItem((target - 3).coerceAtLeast(0))
    }

    fun doSave() {
        val path = filePath
        val uri = songUri
        if (path.isNullOrBlank() || uri == null) {
            scope.launch { snackbarHostState.showSnackbar("No file path available") }
            return
        }
        onSave(path) { success, permDenied ->
            when {
                success -> onSaveSuccess()
                permDenied -> onPermissionNeeded(uri) {
                    onSave(path) { s, _ ->
                        if (s) onSaveSuccess()
                        else scope.launch { snackbarHostState.showSnackbar("Save failed") }
                    }
                }
                else -> scope.launch { snackbarHostState.showSnackbar("Save failed — check file permissions") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Lyrics") },
                navigationIcon = {
                    IconButton(onClick = onBackToEditing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to editing")
                    }
                },
                actions = {
                    val stamped = lines.count { it.timeMs != null }
                    when {
                        isPublishing -> CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                        editorState.isSaving -> CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                        stamped > 0 -> {
                            IconButton(onClick = onPublish) {
                                Icon(Icons.Filled.CloudUpload, contentDescription = "Publish to LRCLib")
                            }
                            IconButton(onClick = ::doSave) {
                                Icon(Icons.Filled.Check, contentDescription = "Save lyrics")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CompactPlayer(playerState, playerViewModel, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            HorizontalDivider()
            LazyColumn(state = lyricsListState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(lines) { index, line ->
                    SyncLineItem(line = line, index = index, currentIndex = currentIndex)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onUndo, enabled = currentIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Undo")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onStamp, enabled = !allStamped, modifier = Modifier.height(52.dp)) {
                        Text(
                            text = if (allStamped) "All stamped!" else "TAP TO STAMP",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${lines.count { it.timeMs != null }} / ${lines.size} lines stamped",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Phase 3: fine-tune individual timestamps ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinetuningPhase(
    editorState: LyricsEditorViewModel.EditorState,
    playerState: PlayerState,
    playerViewModel: PlayerViewModel,
    filePath: String?,
    songUri: Uri?,
    snackbarHostState: SnackbarHostState,
    isPublishing: Boolean,
    onAdjust: (index: Int, deltaMs: Long) -> Unit,
    onSetTimestamp: (index: Int, timeMs: Long) -> Unit,
    onEditText: (index: Int, text: String) -> Unit,
    onRemoveLine: (index: Int) -> Unit,
    onInsertLineAfter: (index: Int) -> Unit,
    onBatchAdjust: (indices: Set<Int>, deltaMs: Long) -> Unit,
    onBackToEditing: () -> Unit,
    onSave: (filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) -> Unit,
    onSaveSuccess: () -> Unit,
    onPermissionNeeded: (Uri, retry: () -> Unit) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onPublish: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lines = editorState.lines

    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    var batchDeltaText by remember { mutableStateOf("100") }
    var dragAnchorIndex by remember { mutableStateOf(-1) }
    val itemWindowTops = remember { mutableStateMapOf<Int, Float>() }
    var dragCurrentWindowY by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(lines.size) { itemWindowTops.clear() }

    LaunchedEffect(dragCurrentWindowY) {
        val y = dragCurrentWindowY ?: return@LaunchedEffect
        val anchor = dragAnchorIndex.takeIf { it >= 0 } ?: return@LaunchedEffect
        val hit = itemWindowTops.entries
            .filter { it.value <= y }
            .maxByOrNull { it.value }?.key ?: anchor
        selectedIndices = (minOf(anchor, hit)..maxOf(anchor, hit)).toSet()
    }

    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 80.dp.toPx() }
    val maxScrollPxPerFrame = with(density) { 10.dp.toPx() }
    var boxWindowTop by remember { mutableStateOf(0f) }
    var boxWindowBottom by remember { mutableStateOf(0f) }

    // While a drag is active and the finger is within the edge threshold,
    // scroll the list and extend the selection into newly-visible items.
    LaunchedEffect(dragAnchorIndex >= 0) {
        while (dragAnchorIndex >= 0) {
            val y = dragCurrentWindowY
            if (y != null) {
                val speed = when {
                    y < boxWindowTop + edgeThresholdPx ->
                        -maxScrollPxPerFrame * ((boxWindowTop + edgeThresholdPx - y) / edgeThresholdPx).coerceIn(0f, 1f)
                    y > boxWindowBottom - edgeThresholdPx ->
                        maxScrollPxPerFrame * ((y - (boxWindowBottom - edgeThresholdPx)) / edgeThresholdPx).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (speed != 0f) {
                    listState.scrollBy(speed)
                    val anchor = dragAnchorIndex
                    if (anchor >= 0) {
                        val hit = itemWindowTops.entries
                            .filter { it.value <= y }
                            .maxByOrNull { it.value }?.key ?: anchor
                        selectedIndices = (minOf(anchor, hit)..maxOf(anchor, hit)).toSet()
                    }
                }
            }
            delay(16L)
        }
    }

    var segmentEndMs by remember { mutableStateOf<Long?>(null) }
    var playingIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(playerState.positionMs) {
        // Auto-pause at the end of the previewed segment
        val end = segmentEndMs
        if (end != null && playerState.positionMs >= end) {
            playerViewModel.pause()
            segmentEndMs = null
            playingIndex = null
        }
        // Prevent auto-advance to next song when the track ends
        val dur = playerState.durationMs
        if (dur > 1_000 && playerState.isPlaying && playerState.positionMs >= dur - 500) {
            playerViewModel.pause()
        }
    }

    // If playback stops for any reason (global pause, segment end), clear segment state
    LaunchedEffect(playerState.isPlaying) {
        if (!playerState.isPlaying) {
            playingIndex = null
            segmentEndMs = null
        }
    }

    fun doSave() {
        val path = filePath
        val uri = songUri
        if (path.isNullOrBlank() || uri == null) {
            scope.launch { snackbarHostState.showSnackbar("No file path available") }
            return
        }
        onSave(path) { success, permDenied ->
            when {
                success -> onSaveSuccess()
                permDenied -> onPermissionNeeded(uri) {
                    onSave(path) { s, _ ->
                        if (s) onSaveSuccess()
                        else scope.launch { snackbarHostState.showSnackbar("Save failed") }
                    }
                }
                else -> scope.launch { snackbarHostState.showSnackbar("Save failed — check file permissions") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fine-tune Timestamps") },
                navigationIcon = {
                    IconButton(onClick = onBackToEditing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (canUndo) {
                        IconButton(onClick = onUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                    }
                    when {
                        isPublishing -> CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                        editorState.isSaving -> CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                        else -> {
                            IconButton(onClick = onPublish, enabled = lines.any { it.timeMs != null }) {
                                Icon(Icons.Filled.CloudUpload, contentDescription = "Publish to LRCLib")
                            }
                            IconButton(onClick = ::doSave) {
                                Icon(Icons.Filled.Check, contentDescription = "Save")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CompactPlayer(playerState, playerViewModel, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            HorizontalDivider()

            // Batch-adjust bar
            BatchAdjustBar(
                lineCount = lines.size,
                selectedIndices = selectedIndices,
                batchDeltaText = batchDeltaText,
                onBatchDeltaChange = { batchDeltaText = it.filter(Char::isDigit).take(5) },
                onToggleSelectAll = {
                    selectedIndices = if (selectedIndices.size == lines.size) emptySet()
                                     else lines.indices.toSet()
                },
                onBatchApply = { delta ->
                    onBatchAdjust(selectedIndices, delta)
                    selectedIndices = emptySet()
                },
            )
            HorizontalDivider()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        boxWindowTop = coords.positionInWindow().y
                        boxWindowBottom = boxWindowTop + coords.size.height.toFloat()
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val windowY = boxWindowTop + offset.y
                                val hit = itemWindowTops.entries
                                    .filter { it.value <= windowY }
                                    .maxByOrNull { it.value }?.key ?: -1
                                if (hit >= 0) {
                                    dragAnchorIndex = hit
                                    selectedIndices = setOf(hit)
                                    dragCurrentWindowY = windowY
                                }
                            },
                            onDrag = { change, _ ->
                                if (dragAnchorIndex >= 0) {
                                    dragCurrentWindowY = boxWindowTop + change.position.y
                                }
                            },
                            onDragEnd = {
                                dragCurrentWindowY = null
                                dragAnchorIndex = -1
                            },
                            onDragCancel = {
                                dragCurrentWindowY = null
                                dragAnchorIndex = -1
                            },
                        )
                    },
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { index, line ->
                        FinetuneLineItem(
                            line = line,
                            isSelected = index in selectedIndices,
                            selectionMode = selectedIndices.isNotEmpty(),
                            isSegmentPlaying = index == playingIndex && playerState.isPlaying,
                            onToggleSelect = {
                                selectedIndices = if (index in selectedIndices) selectedIndices - index
                                                else selectedIndices + index
                            },
                            onPlaySegment = {
                                playerViewModel.seekTo(line.timeMs ?: 0L)
                                if (!playerState.isPlaying) playerViewModel.togglePlayPause()
                                segmentEndMs = lines.getOrNull(index + 1)?.timeMs
                                playingIndex = index
                            },
                            onStopSegment = {
                                playerViewModel.pause()
                                segmentEndMs = null
                                playingIndex = null
                            },
                            onAdjust = { delta -> onAdjust(index, delta) },
                            onSetTimestamp = { ms -> onSetTimestamp(index, ms) },
                            onEditText = { text -> onEditText(index, text) },
                            onRemove = {
                                onRemoveLine(index)
                                selectedIndices = emptySet()
                            },
                            onPositioned = { windowTop -> itemWindowTops[index] = windowTop },
                        )
                        InsertLineDivider {
                            onInsertLineAfter(index)
                            selectedIndices = emptySet()
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

// ── Batch-adjust bar ──────────────────────────────────────────────────────────

@Composable
private fun BatchAdjustBar(
    lineCount: Int,
    selectedIndices: Set<Int>,
    batchDeltaText: String,
    onBatchDeltaChange: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onBatchApply: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggleSelectAll) {
            Text(
                if (selectedIndices.size == lineCount && lineCount > 0) "Deselect all" else "Select all",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (selectedIndices.isNotEmpty()) {
            Text(
                "${selectedIndices.size} / $lineCount selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (selectedIndices.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            val delta = batchDeltaText.toLongOrNull() ?: 0L
            OutlinedTextField(
                value = batchDeltaText,
                onValueChange = onBatchDeltaChange,
                modifier = Modifier.width(76.dp),
                singleLine = true,
                label = { Text("ms") },
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = { onBatchApply(-delta) },
                enabled = delta > 0,
                modifier = Modifier.height(40.dp),
            ) { Text("−") }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { onBatchApply(+delta) },
                enabled = delta > 0,
                modifier = Modifier.height(40.dp),
            ) { Text("+") }
        }
    }
}

// ── Fine-tune line item ────────────────────────────────────────────────────────

@Composable
private fun FinetuneLineItem(
    line: LyricsEditorViewModel.EditorLine,
    isSelected: Boolean,
    selectionMode: Boolean,
    isSegmentPlaying: Boolean,
    onToggleSelect: () -> Unit,
    onPlaySegment: () -> Unit,
    onStopSegment: () -> Unit,
    onAdjust: (Long) -> Unit,
    onSetTimestamp: (Long) -> Unit,
    onEditText: (String) -> Unit,
    onRemove: () -> Unit,
    onPositioned: (windowTop: Float) -> Unit,
) {
    val timeMs = line.timeMs ?: 0L
    val minVal = (timeMs / 60_000).toInt()
    val secVal = ((timeMs % 60_000) / 1_000).toInt()
    val csVal = ((timeMs % 1_000) / 10).toInt()

    // Track the last ms value we pushed to the ViewModel so LaunchedEffect can
    // tell the difference between an "external" change (± button) and our own edit.
    var lastSetMs by remember { mutableStateOf(timeMs) }
    var minText by remember { mutableStateOf(minVal.toString().padStart(2, '0')) }
    var secText by remember { mutableStateOf(secVal.toString().padStart(2, '0')) }
    var csText by remember { mutableStateOf(csVal.toString().padStart(2, '0')) }

    LaunchedEffect(timeMs) {
        if (timeMs != lastSetMs) {
            minText = (timeMs / 60_000).toString().padStart(2, '0')
            secText = ((timeMs % 60_000) / 1_000).toString().padStart(2, '0')
            csText = ((timeMs % 1_000) / 10).toString().padStart(2, '0')
            lastSetMs = timeMs
        }
    }

    fun commit(mStr: String, sStr: String, cStr: String) {
        val m = mStr.toIntOrNull() ?: return
        val s = sStr.toIntOrNull()?.coerceIn(0, 59) ?: return
        val c = cStr.toIntOrNull()?.coerceIn(0, 99) ?: return
        val ms = m * 60_000L + s * 1_000L + c * 10L
        lastSetMs = ms
        onSetTimestamp(ms)
    }

    val bgColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surface,
        label = "sel_bg",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> onPositioned(coords.positionInWindow().y) }
            .background(bgColor)
            .clickable(enabled = selectionMode, onClick = onToggleSelect),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = line.text,
                onValueChange = onEditText,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text("Lyric text", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                },
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }

        // Timestamp editor + ±100ms buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = if (isSegmentPlaying) onStopSegment else onPlaySegment,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (isSegmentPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isSegmentPlaying) "Stop segment" else "Play segment",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(6.dp))
            TsField(value = minText, label = "mm", onValueChange = {
                minText = it.filter(Char::isDigit).take(2)
                commit(minText, secText, csText)
            })
            Text(":", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 2.dp))
            TsField(value = secText, label = "ss", onValueChange = {
                secText = it.filter(Char::isDigit).take(2)
                commit(minText, secText, csText)
            })
            Text(".", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 2.dp))
            TsField(value = csText, label = "cs", onValueChange = {
                csText = it.filter(Char::isDigit).take(2)
                commit(minText, secText, csText)
            })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onAdjust(-100L) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "−100ms", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onAdjust(+100L) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "+100ms", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Compact timestamp field (mm / ss / cs) ────────────────────────────────────

@Composable
private fun TsField(value: String, label: String, onValueChange: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Tappable insert-line divider between items ────────────────────────────────

@Composable
private fun InsertLineDivider(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Icon(
            Icons.Filled.Add,
            contentDescription = "Insert line here",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ── Single line item in the syncing list ─────────────────────────────────────

@Composable
private fun SyncLineItem(
    line: LyricsEditorViewModel.EditorLine,
    index: Int,
    currentIndex: Int,
) {
    val isCurrent = index == currentIndex
    val isPast = index < currentIndex

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .alpha(if (isCurrent || isPast) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val timestamp = line.timeMs?.let { ms ->
            "%02d:%02d.%02d".format(ms / 60_000, (ms % 60_000) / 1_000, (ms % 1_000) / 10)
        } ?: if (isCurrent) "→" else "——:——.——"

        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Compact playback controls (shared between Sync and Fine-tune) ─────────────

@Composable
private fun CompactPlayer(
    playerState: PlayerState,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val sliderValue = dragPosition
        ?: if (playerState.durationMs > 0) playerState.positionMs.toFloat() / playerState.durationMs else 0f

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { playerViewModel.skipPrevious() }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = { playerViewModel.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = { playerViewModel.skipNext() }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(22.dp))
        }
        Slider(
            value = sliderValue,
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { f -> playerViewModel.seekTo((f * playerState.durationMs).toLong()) }
                dragPosition = null
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${formatDuration(playerState.positionMs)} / ${formatDuration(playerState.durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
