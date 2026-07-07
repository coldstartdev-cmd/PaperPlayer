package com.jp.paperplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.jp.paperplayer.model.ui.PlayerState
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import kotlin.math.abs
import kotlinx.coroutines.launch

// ── Mini-player: persistent bar shown on all screens except the player.
// Fully gesture-driven — tap toggles play/pause, swipe left/right skips
// tracks, swipe up opens the now-playing screen. The card itself never moves;
// a gradient grows from the point of contact toward the swipe direction,
// previewing which action will fire. ────────────────────────────────────────

private const val DRAG_THRESHOLD_DP = 24

@Composable
fun MiniPlayer(
    state: PlayerState,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    val fillColor = cs.primaryContainer

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { DRAG_THRESHOLD_DP.dp.toPx() }

    // Point of contact and the finger's current position, used to draw the
    // directional gesture-preview gradient. revealStrength (0..1) drives its
    // opacity and springs back to 0 once the finger lifts.
    var gestureAnchor by remember { mutableStateOf(Offset.Zero) }
    var gestureCurrent by remember { mutableStateOf(Offset.Zero) }
    val revealStrength = remember { Animatable(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.surfaceContainer)
            // Applied to the whole bar (not just the card) so the divider and the
            // card's own padding both sit clear of the gesture-nav pill/hint.
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(onTogglePlayPause, onSkipNext, onSkipPrevious, onOpenPlayer, dragThresholdPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            gestureAnchor = down.position
                            gestureCurrent = down.position
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                gestureCurrent += change.positionChange()
                                if (change.positionChanged()) change.consume()
                                val dx = gestureCurrent.x - gestureAnchor.x
                                val dy = gestureCurrent.y - gestureAnchor.y
                                scope.launch {
                                    revealStrength.snapTo(
                                        (maxOf(abs(dx), abs(dy)) / dragThresholdPx).coerceIn(0f, 1f)
                                    )
                                }
                            } while (event.changes.any { it.id == down.id && it.pressed })

                            val dx = gestureCurrent.x - gestureAnchor.x
                            val dy = gestureCurrent.y - gestureAnchor.y
                            when {
                                abs(dx) < dragThresholdPx && abs(dy) < dragThresholdPx ->
                                    onTogglePlayPause()
                                abs(dx) > abs(dy) ->
                                    if (dx > 0) onSkipNext() else onSkipPrevious()
                                dy < 0 -> onOpenPlayer()
                            }
                            scope.launch { revealStrength.animateTo(0f, spring()) }
                        }
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                drawRect(
                                    color = fillColor,
                                    size = Size(size.width * progress, size.height),
                                )
                            }
                            .then(
                                if (revealStrength.value > 0.01f) {
                                    Modifier.background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                cs.primary.copy(alpha = revealStrength.value * 0.85f),
                                            ),
                                            start = gestureAnchor,
                                            end = gestureCurrent,
                                        )
                                    )
                                } else Modifier
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(cs.primaryContainer),
                        ) {
                            AsyncImage(
                                model = state.currentSong?.albumArtUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (state.isPlaying) Modifier.blur(6.dp) else Modifier),
                                contentScale = ContentScale.Crop,
                            )
                            if (state.isPlaying) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = formatDuration(state.positionMs),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.currentSong?.title ?: "",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.currentSong?.artist ?: "",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.currentSong?.let { song ->
                                    if (song.year > 0) "${song.album} · ${song.year}" else song.album
                                } ?: "",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = state.currentSong?.duration?.let { formatDuration(it) } ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurfaceVariant,
                        )
                    }

                    // Gesture action-preview icon — fades in with the gradient.
                    if (revealStrength.value > 0.01f) {
                        val dx = gestureCurrent.x - gestureAnchor.x
                        val dy = gestureCurrent.y - gestureAnchor.y
                        val (previewIcon, previewAlignment) = when {
                            abs(dx) > abs(dy) ->
                                if (dx > 0) Icons.Filled.SkipNext to Alignment.CenterEnd
                                else Icons.Filled.SkipPrevious to Alignment.CenterStart
                            else -> Icons.Filled.KeyboardArrowUp to Alignment.TopCenter
                        }
                        Icon(
                            previewIcon,
                            contentDescription = null,
                            tint = cs.onPrimary,
                            modifier = Modifier
                                .align(previewAlignment)
                                .padding(10.dp)
                                .graphicsLayer { alpha = revealStrength.value },
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MiniPlayerPreview() {
    PaperPlayerTheme {
        MiniPlayer(
            state = PreviewFixtures.playerState,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onOpenPlayer = {},
        )
    }
}

@Preview(showBackground = true, name = "Paused")
@Composable
private fun MiniPlayerPausedPreview() {
    PaperPlayerTheme {
        MiniPlayer(
            state = PreviewFixtures.playerState.copy(isPlaying = false),
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onOpenPlayer = {},
        )
    }
}
