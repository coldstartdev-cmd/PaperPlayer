package com.jp.paperplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jp.paperplayer.model.ui.PlayerState
import com.jp.paperplayer.ui.components.formatDuration
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

// ── Fullscreen immersive lyrics — tap anywhere to toggle controls overlay ─────

@Composable
fun FullscreenLyricsLayout(
    state: PlayerState,
    lyricsListState: LazyListState,
    controlsVisible: Boolean,
    shuffleEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    onTap: () -> Unit,
    onExitFullscreen: () -> Unit,
    onNavigateBack: () -> Unit,
    onToggleShuffle: () -> Unit,
    onTranslate: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
) {
    // True black (not just the theme's dark surface) in dark mode — saves power on
    // AMOLED screens and gives lyrics the most contrast in this "cinema mode" view.
    val background = if (isSystemInDarkTheme()) Color.Black else MaterialTheme.colorScheme.background
    val displayLyrics = state.displayLyrics

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
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
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 80.dp),
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
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        // Top overlay: back | song title | exit fullscreen
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(background.copy(alpha = 0.88f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
                    if (!state.currentSong?.album.isNullOrBlank()) {
                        Text(
                            text = state.currentSong?.album ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                    if (state.isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp))
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
                    IconButton(onClick = onExitFullscreen) {
                        Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit fullscreen")
                    }
                }
            }
        }

        // Bottom overlay: font size slider + seek bar + playback controls
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            var dragPosition by remember { mutableStateOf<Float?>(null) }
            val sliderValue = dragPosition
                ?: if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, background.copy(alpha = 0.88f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = sliderValue,
                        onValueChange = { dragPosition = it },
                        onValueChangeFinished = {
                            dragPosition?.let { f ->
                                onSeek((f * state.durationMs).toLong())
                            }
                            dragPosition = null
                        },
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            formatDuration(state.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatDuration(state.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.size(28.dp),
                            tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onSkipPrevious) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
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
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = onSkipNext) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "With lyrics")
@Composable
internal fun FullscreenLyricsLayoutPreview() {
    PaperPlayerTheme {
        FullscreenLyricsLayout(
            state = PreviewFixtures.playerState,
            lyricsListState = androidx.compose.foundation.lazy.rememberLazyListState(),
            controlsVisible = true,
            shuffleEnabled = true,
            snackbarHostState = remember { SnackbarHostState() },
            onTap = {},
            onExitFullscreen = {},
            onNavigateBack = {},
            onToggleShuffle = {},
            onTranslate = {},
            onSeek = {},
            onSkipPrevious = {},
            onTogglePlayPause = {},
            onSkipNext = {},
        )
    }
}

@Preview(showBackground = true, name = "No lyrics, controls hidden")
@Composable
private fun FullscreenLyricsLayoutHiddenControlsPreview() {
    PaperPlayerTheme {
        FullscreenLyricsLayout(
            state = PreviewFixtures.playerStateNoLyrics,
            lyricsListState = androidx.compose.foundation.lazy.rememberLazyListState(),
            controlsVisible = false,
            shuffleEnabled = false,
            snackbarHostState = remember { SnackbarHostState() },
            onTap = {},
            onExitFullscreen = {},
            onNavigateBack = {},
            onToggleShuffle = {},
            onTranslate = {},
            onSeek = {},
            onSkipPrevious = {},
            onTogglePlayPause = {},
            onSkipNext = {},
        )
    }
}
