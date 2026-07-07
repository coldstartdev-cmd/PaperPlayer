package com.jp.paperplayer.ui.tageditor

import android.app.Activity
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.model.ui.TagEditorState
import com.jp.paperplayer.ui.player.PlayerViewModel
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.songlist.SongListViewModel
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorScreen(
    song: Song,
    songListViewModel: SongListViewModel,
    playerViewModel: PlayerViewModel,
    editorViewModel: TagEditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLyricsEditor: () -> Unit,
) {
    val state by editorViewModel.state.collectAsStateWithLifecycle()
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

    LaunchedEffect(song.filePath) { editorViewModel.load(song.filePath) }

    fun onSave() {
        editorViewModel.save(song.filePath) { success, permissionDenied ->
            when {
                success -> {
                    MediaScannerConnection.scanFile(context, arrayOf(song.filePath), null) { _, _ ->
                        songListViewModel.loadSongs()
                    }
                    onNavigateBack()
                }
                permissionDenied -> onPermissionNeeded(song.uri) { onSave() }
                else -> scope.launch { snackbarHostState.showSnackbar("Save failed") }
            }
        }
    }

    TagEditorContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onTitleChange = editorViewModel::updateTitle,
        onArtistChange = editorViewModel::updateArtist,
        onAlbumChange = editorViewModel::updateAlbum,
        onGenreChange = editorViewModel::updateGenre,
        onYearChange = editorViewModel::updateYear,
        onTrackNumberChange = editorViewModel::updateTrackNumber,
        onLanguageChange = editorViewModel::updateLanguage,
        onEditLyrics = {
            playerViewModel.loadForEditing(song)
            onNavigateToLyricsEditor()
        },
        onSave = ::onSave,
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagEditorContent(
    state: TagEditorState,
    snackbarHostState: SnackbarHostState,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onGenreChange: (String) -> Unit,
    onYearChange: (String) -> Unit,
    onTrackNumberChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onEditLyrics: () -> Unit,
    onSave: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Tags") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                    } else {
                        IconButton(onClick = onSave, enabled = !state.isLoading) {
                            Icon(Icons.Filled.Check, contentDescription = "Save tags")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.artist,
                onValueChange = onArtistChange,
                label = { Text("Artist") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.album,
                onValueChange = onAlbumChange,
                label = { Text("Album") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.genre,
                onValueChange = onGenreChange,
                label = { Text("Genre") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.year,
                onValueChange = onYearChange,
                label = { Text("Year") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = state.trackNumber,
                onValueChange = onTrackNumberChange,
                label = { Text("Track Number") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = state.language,
                onValueChange = onLanguageChange,
                label = { Text("Language") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                singleLine = true,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = "Lyrics",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = when {
                        state.hasSyncedLyrics -> "Synced lyrics (${state.syncedLineCount} lines)"
                        state.hasUnsyncedLyrics -> "Unsynced lyrics present"
                        else -> "No lyrics"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onEditLyrics,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Edit Lyrics")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun TagEditorContentPreview() {
    PaperPlayerTheme {
        TagEditorContent(
            state = PreviewFixtures.tagEditorState,
            snackbarHostState = remember { SnackbarHostState() },
            onTitleChange = {},
            onArtistChange = {},
            onAlbumChange = {},
            onGenreChange = {},
            onYearChange = {},
            onTrackNumberChange = {},
            onLanguageChange = {},
            onEditLyrics = {},
            onSave = {},
            onNavigateBack = {},
        )
    }
}
