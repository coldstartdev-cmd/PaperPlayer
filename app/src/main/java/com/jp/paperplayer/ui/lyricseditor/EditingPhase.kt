package com.jp.paperplayer.ui.lyricseditor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jp.paperplayer.model.data.LrcTrack
import com.jp.paperplayer.model.ui.LyricsEditorState
import com.jp.paperplayer.model.ui.SearchStatus
import com.jp.paperplayer.ui.components.formatDuration
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import kotlinx.coroutines.launch

// ── Phase 1: type / paste all lyric lines ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditingPhase(
    editorState: LyricsEditorState,
    hasExistingLyrics: Boolean,
    songTitle: String?,
    songArtist: String?,
    songDurationMs: Long?,
    filePath: String?,
    songUri: Uri?,
    snackbarHostState: SnackbarHostState,
    searchTitle: String,
    searchArtist: String,
    searchAlbum: String,
    searchResults: List<LrcTrack>,
    searchStatus: SearchStatus,
    selectedTrack: LrcTrack?,
    onRawTextChange: (String) -> Unit,
    onSaveDirectly: (filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) -> Unit,
    onSaveSuccess: () -> Unit,
    onPermissionNeeded: (Uri, retry: () -> Unit) -> Unit,
    onStartSyncing: () -> Unit,
    onStartFinetuning: () -> Unit,
    isPublishing: Boolean,
    onPublish: () -> Unit,
    onNavigateBack: () -> Unit,
    onInitSearchFields: (title: String) -> Unit,
    onSearchTitleChange: (String) -> Unit,
    onSearchArtistChange: (String) -> Unit,
    onSearchAlbumChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectTrack: (LrcTrack) -> Unit,
    onUseTrackSynced: () -> Unit,
    onUseTrackAsPlain: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(filePath) {
        if (songTitle != null) onInitSearchFields(songTitle)
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
                            val subtitle = listOfNotNull(
                                songArtist?.takeIf { it.isNotBlank() },
                                songDurationMs?.let { formatDuration(it) },
                            ).joinToString("  ·  ")
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
                    onStartSyncing = onStartSyncing,
                    onStartFinetuning = onStartFinetuning,
                    isPublishing = isPublishing,
                    onPublish = onPublish,
                )
                1 -> SearchTab(
                    searchTitle = searchTitle,
                    searchArtist = searchArtist,
                    searchAlbum = searchAlbum,
                    searchResults = searchResults,
                    searchStatus = searchStatus,
                    selectedTrack = selectedTrack,
                    onTitleChange = onSearchTitleChange,
                    onArtistChange = onSearchArtistChange,
                    onAlbumChange = onSearchAlbumChange,
                    onSearch = onSearch,
                    onSelectTrack = onSelectTrack,
                    onUseTrackSynced = onUseTrackSynced,
                    onUseTrackAsPlain = {
                        // Loads the text into the ViewModel's rawText, but that alone leaves you
                        // stranded on this tab with no visible way to reach it or the save button
                        // (which only renders while selectedTab == 0) — switch tabs so the loaded
                        // text and the save action are actually visible.
                        onUseTrackAsPlain()
                        selectedTab = 0
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EditingPhasePreview() {
    PaperPlayerTheme {
        EditingPhase(
            editorState = LyricsEditorState(rawText = PreviewFixtures.lyrics.joinToString("\n") { it.text }),
            hasExistingLyrics = false,
            songTitle = PreviewFixtures.song1.title,
            songArtist = PreviewFixtures.song1.artist,
            songDurationMs = PreviewFixtures.song1.duration,
            filePath = PreviewFixtures.song1.filePath,
            songUri = PreviewFixtures.song1.uri,
            snackbarHostState = remember { SnackbarHostState() },
            searchTitle = PreviewFixtures.song1.title,
            searchArtist = "",
            searchAlbum = "",
            searchResults = PreviewFixtures.lrcTracks,
            searchStatus = SearchStatus.Idle,
            selectedTrack = null,
            onRawTextChange = {},
            onSaveDirectly = { _, _ -> },
            onSaveSuccess = {},
            onPermissionNeeded = { _, _ -> },
            onStartSyncing = {},
            onStartFinetuning = {},
            isPublishing = false,
            onPublish = {},
            onNavigateBack = {},
            onInitSearchFields = {},
            onSearchTitleChange = {},
            onSearchArtistChange = {},
            onSearchAlbumChange = {},
            onSearch = {},
            onSelectTrack = {},
            onUseTrackSynced = {},
            onUseTrackAsPlain = {},
        )
    }
}

@Composable
private fun WriteTab(
    editorState: LyricsEditorState,
    hasExistingLyrics: Boolean,
    onRawTextChange: (String) -> Unit,
    onStartSyncing: () -> Unit,
    onStartFinetuning: () -> Unit,
    isPublishing: Boolean,
    onPublish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

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
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasExistingLyrics) {
                OutlinedButton(onClick = onStartFinetuning, modifier = Modifier.weight(1f)) {
                    Text("Fine-tune")
                }
            }
            Button(
                onClick = onStartSyncing,
                enabled = editorState.rawText.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Sync →")
            }
        }

        // True once any line carries a real timestamp — either because the file already had
        // synced lyrics (loadFromExisting pre-populates `lines` in that case), or because this
        // session went through syncing/fine-tuning and came back here via the back button
        // (which doesn't clear editorState.lines).
        val hasTimedLines = editorState.lines.any { it.timeMs != null }
        if (hasTimedLines) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onPublish,
                enabled = !isPublishing,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Publish to LRCLib")
                }
            }
        }
    }
}

@Composable
private fun SearchTab(
    searchTitle: String,
    searchArtist: String,
    searchAlbum: String,
    searchResults: List<LrcTrack>,
    searchStatus: SearchStatus,
    selectedTrack: LrcTrack?,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectTrack: (LrcTrack) -> Unit,
    onUseTrackSynced: () -> Unit,
    onUseTrackAsPlain: () -> Unit,
) {
    var formExpanded by remember { mutableStateOf(true) }
    var showArtistAlbum by remember { mutableStateOf(false) }

    // Collapse the form once results are in, so there's more room to view them.
    LaunchedEffect(searchStatus) {
        if (searchStatus == SearchStatus.Done) formExpanded = false
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Search fields
        if (formExpanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = searchTitle,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Title") },
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showArtistAlbum = !showArtistAlbum
                            // Hidden fields shouldn't silently keep narrowing the search —
                            // clear them so unchecking actually means "not used".
                            if (!showArtistAlbum) {
                                onArtistChange("")
                                onAlbumChange("")
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = showArtistAlbum, onCheckedChange = null)
                    Text("Search by artist / album too", style = MaterialTheme.typography.bodySmall)
                }
                if (showArtistAlbum) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = searchArtist,
                        onValueChange = onArtistChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Artist (optional)") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchAlbum,
                        onValueChange = onAlbumChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Album (optional)") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onSearch,
                    enabled = searchTitle.isNotBlank() && searchStatus != SearchStatus.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (searchStatus == SearchStatus.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { formExpanded = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = searchTitle.ifBlank { "Search" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        when (searchStatus) {
            SearchStatus.NoResults -> {
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

                // Full-size preview dialog for the selected track
                if (selectedTrack != null) {
                    TrackPreviewDialog(
                        track = selectedTrack,
                        onDismiss = { onSelectTrack(selectedTrack) },
                        onUseTrackSynced = onUseTrackSynced,
                        onUseTrackAsPlain = onUseTrackAsPlain,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackPreviewDialog(
    track: LrcTrack,
    onDismiss: () -> Unit,
    onUseTrackSynced: () -> Unit,
    onUseTrackAsPlain: () -> Unit,
) {
    val hasSynced = track.syncedLyrics != null
    val hasPlain = track.plainLyrics != null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = track.trackName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${track.artistName}  ·  ${track.albumName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(16.dp))

                val previewText = track.plainLyrics
                    ?: track.syncedLyrics
                    ?: "No lyrics text available"
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasSynced) {
                        FilledTonalButton(onClick = onUseTrackSynced, modifier = Modifier.weight(1f)) {
                            Text("Use with Timing", maxLines = 1)
                        }
                    }
                    if (hasPlain) {
                        OutlinedButton(onClick = onUseTrackAsPlain, modifier = Modifier.weight(1f)) {
                            Text("Use Plain Text", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    track: LrcTrack,
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
            if (track.plainLyrics != null) {
                Text(
                    text = "plain",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
