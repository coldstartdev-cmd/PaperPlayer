package com.jp.paperplayer.ui.lyricseditor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jp.paperplayer.model.ui.PlayerState
import com.jp.paperplayer.ui.components.formatDuration
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

// ── Compact playback controls (shared between Sync and Fine-tune) ─────────────

@Composable
fun CompactPlayer(
    playerState: PlayerState,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val sliderValue = dragPosition
        ?: if (playerState.durationMs > 0) playerState.positionMs.toFloat() / playerState.durationMs else 0f

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onSkipPrevious, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = onSkipNext, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(22.dp))
        }
        Slider(
            value = sliderValue,
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { f -> onSeek((f * playerState.durationMs).toLong()) }
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

@Preview(showBackground = true)
@Composable
private fun CompactPlayerPreview() {
    PaperPlayerTheme {
        CompactPlayer(
            playerState = PreviewFixtures.playerState,
            onSkipPrevious = {},
            onTogglePlayPause = {},
            onSkipNext = {},
            onSeek = {},
        )
    }
}
