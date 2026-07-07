package com.jp.paperplayer.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jp.paperplayer.model.ui.PlayerState
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

// ── Landscape: slim top bar + lyrics ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandscapeLyricsLayout(
    state: PlayerState,
    lyricsListState: LazyListState,
    shuffleEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onNavigateToEditor: () -> Unit,
    onNavigateToQueue: () -> Unit = {},
    onToggleShuffle: () -> Unit,
    onTranslate: () -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
) {
    val displayLyrics = state.displayLyrics

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.currentSong?.title ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.currentSong?.artist ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSkipPrevious) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = onSkipNext) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                    IconButton(onClick = onEnterFullscreen) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen")
                    }
                    if (state.isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        IconButton(onClick = onTranslate) {
                            if (state.activeLanguage != null) {
                                Text(
                                    state.activeLanguage.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(Icons.Filled.GTranslate, contentDescription = "Language")
                            }
                        }
                    }
                    IconButton(onClick = onNavigateToEditor) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit lyrics")
                    }
                    IconButton(onClick = onNavigateToQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (displayLyrics.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.isTranslating) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = "No lyrics available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = lyricsListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    itemsIndexed(displayLyrics) { index, line ->
                        LyricLineItem(
                            text = line.text,
                            index = index,
                            currentIndex = state.currentLyricIndex,
                            timeMs = line.timeMs,
                            currentTimeMs = displayLyrics.getOrNull(state.currentLyricIndex)?.timeMs,
                        )
                    }
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 400, name = "With lyrics")
@Composable
private fun LandscapeLyricsLayoutPreview() {
    PaperPlayerTheme {
        LandscapeLyricsLayout(
            state = PreviewFixtures.playerState,
            lyricsListState = androidx.compose.foundation.lazy.rememberLazyListState(),
            shuffleEnabled = true,
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onEnterFullscreen = {},
            onNavigateToEditor = {},
            onToggleShuffle = {},
            onTranslate = {},
            onSkipPrevious = {},
            onTogglePlayPause = {},
            onSkipNext = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 400, name = "No lyrics")
@Composable
private fun LandscapeLyricsLayoutNoLyricsPreview() {
    PaperPlayerTheme {
        LandscapeLyricsLayout(
            state = PreviewFixtures.playerStateNoLyrics,
            lyricsListState = androidx.compose.foundation.lazy.rememberLazyListState(),
            shuffleEnabled = false,
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onEnterFullscreen = {},
            onNavigateToEditor = {},
            onToggleShuffle = {},
            onTranslate = {},
            onSkipPrevious = {},
            onTogglePlayPause = {},
            onSkipNext = {},
        )
    }
}
