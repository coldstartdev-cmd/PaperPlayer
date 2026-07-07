package com.jp.paperplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jp.paperplayer.model.ui.PlayerState
import com.jp.paperplayer.ui.components.formatDuration
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

// ── Portrait: album art sits between the title and controls; lyrics blur it ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortraitPlayerLayout(
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
    onSeek: (Long) -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
) {
    val displayLyrics = state.displayLyrics
    val hasLyrics = displayLyrics.isNotEmpty()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onSurface)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onEnterFullscreen) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen", tint = onSurface)
            }
            if (state.isTranslating) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = onSurface)
            } else {
                IconButton(onClick = onTranslate) {
                    if (state.activeLanguage != null) {
                        Text(
                            state.activeLanguage.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(Icons.Filled.GTranslate, contentDescription = "Language", tint = onSurface)
                    }
                }
            }
            IconButton(onClick = onNavigateToEditor) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit lyrics", tint = onSurface)
            }
            IconButton(onClick = onNavigateToQueue) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue", tint = onSurface)
            }
        }

        // Song title + artist (below the top bar)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = state.currentSong?.title ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.currentSong?.artist ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (!state.currentSong?.album.isNullOrBlank()) {
                Text(
                    text = state.currentSong?.album ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }

        // ── Album art, between the title and the controls. Square when there's
        // nothing else to show; blurred with lyrics scrolling over it otherwise. ─
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = (if (hasLyrics) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(1f))
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                AsyncImage(
                    model = state.currentSong?.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (hasLyrics) Modifier.blur(28.dp) else Modifier),
                    contentScale = ContentScale.Crop,
                )
                when {
                    state.isTranslating -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    hasLyrics -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                        )
                        LazyColumn(
                            state = lyricsListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
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
                                    overrideColors = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Controls pinned at bottom ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        ) {
            var dragPosition by remember { mutableStateOf<Float?>(null) }
            val sliderValue = dragPosition
                ?: if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f

            Slider(
                value = sliderValue,
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    dragPosition?.let { fraction ->
                        onSeek((fraction * state.durationMs).toLong())
                    }
                    dragPosition = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                Spacer(modifier = Modifier.weight(1f))
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
            }

            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                IconButton(onClick = onToggleShuffle, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        modifier = Modifier.size(28.dp),
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onSkipPrevious) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp), tint = onSurface)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(onClick = onSkipNext) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp), tint = onSurface)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "With lyrics")
@Composable
private fun PortraitPlayerLayoutPreview() {
    PaperPlayerTheme {
        PortraitPlayerLayout(
            state = PreviewFixtures.playerState,
            lyricsListState = androidx.compose.foundation.lazy.rememberLazyListState(),
            shuffleEnabled = true,
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onEnterFullscreen = {},
            onNavigateToEditor = {},
            onToggleShuffle = {},
            onTranslate = {},
            onSeek = {},
            onSkipPrevious = {},
            onTogglePlayPause = {},
            onSkipNext = {},
        )
    }
}

@Preview(showBackground = true, name = "No lyrics")
@Composable
private fun PortraitPlayerLayoutNoLyricsPreview() {
    PaperPlayerTheme {
        PortraitPlayerLayout(
            state = PreviewFixtures.playerStateNoLyrics,
            lyricsListState = androidx.compose.foundation.lazy.rememberLazyListState(),
            shuffleEnabled = false,
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onEnterFullscreen = {},
            onNavigateToEditor = {},
            onToggleShuffle = {},
            onTranslate = {},
            onSeek = {},
            onSkipPrevious = {},
            onTogglePlayPause = {},
            onSkipNext = {},
        )
    }
}
