package com.jp.paperplayer.ui.party

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.ui.PartySyncDebug
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.model.ui.SyncQuality

@Composable
internal fun GuestView(
    state: PartyUiState,
    latencyTrimMs: Long,
    isCalibrating: Boolean,
    calibrationMessage: String?,
    suggestedTrimMs: Long?,
    onApplySuggestedTrim: () -> Unit,
    onDismissSuggestedTrim: () -> Unit,
    onLatencyTrimChange: (Long) -> Unit,
    onCalibrate: () -> Unit,
    onLeave: () -> Unit,
    myEqRole: PartyEqRole = PartyEqRole.NONE,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Groups,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(text = state.partyName, style = MaterialTheme.typography.headlineSmall)
        state.connectedHostName?.let { host ->
            Text(
                text = "Hosted by $host",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (myEqRole != PartyEqRole.NONE) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "Speaker role: ${myEqRole.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SyncQualityChip(quality = state.syncQuality, rttMs = state.rttMs)
        state.nowPlaying?.let { nowPlaying ->
            Spacer(Modifier.height(24.dp))
            if (state.isDownloading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Getting ready…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = nowPlaying,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.startsInMs?.let { remaining ->
            Spacer(Modifier.height(12.dp))
            StartCountdown(remainingMs = remaining)
        }
        Spacer(Modifier.height(20.dp))
        LatencyTrimSlider(trimMs = latencyTrimMs, onTrimChange = onLatencyTrimChange)

        OutlinedButton(onClick = onCalibrate, enabled = !isCalibrating) {
            if (isCalibrating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text(if (isCalibrating) "Calibrating…" else "Calibrate automatically")
        }
        calibrationMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        suggestedTrimMs?.let { suggested ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Measured ideal trim: ${formatTrim(suggested)} (now ${formatTrim(latencyTrimMs)})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row {
                        TextButton(onClick = onDismissSuggestedTrim) { Text("Keep mine") }
                        TextButton(onClick = onApplySuggestedTrim) { Text("Apply") }
                    }
                }
            }
        }

        var showDebug by remember { mutableStateOf(false) }
        TextButton(onClick = { showDebug = !showDebug }) {
            Text(if (showDebug) "Hide sync details" else "Show sync details")
        }
        if (showDebug) {
            SyncDebugPanel(debug = state.syncDebug)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onLeave) {
            Text("Leave party")
        }
    }
}

@Composable
internal fun SyncDebugPanel(debug: PartySyncDebug) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        DebugRow("Clock offset", debug.clockOffsetMs?.let { "$it ms" } ?: "—")
        DebugRow("Median RTT", debug.medianRttMs?.let { "$it ms" } ?: "—")
        DebugRow("Drift", debug.lastDriftMs?.let { "$it ms" } ?: "—")
        DebugRow("Playback speed", "${debug.playbackSpeed}x")
        DebugRow(
            "Expected / actual",
            "${formatDebugSeconds(debug.expectedPositionMs)} / ${formatDebugSeconds(debug.actualPositionMs)}",
        )
        DebugRow("Corrections", "${debug.nudgeCorrections} nudges · ${debug.seekCorrections} seeks")
        DriftSparkline(history = debug.driftHistory)
    }
}

@Composable
private fun LatencyTrimSlider(trimMs: Long, onTrimChange: (Long) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Audio latency trim",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onTrimChange((trimMs - TRIM_STEP_MS).coerceAtLeast(TRIM_RANGE_MS.first)) },
                enabled = trimMs > TRIM_RANGE_MS.first,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Trim 5 ms earlier")
            }
            Text(
                text = "${if (trimMs > 0) "+" else ""}$trimMs ms",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            IconButton(
                onClick = { onTrimChange((trimMs + TRIM_STEP_MS).coerceAtMost(TRIM_RANGE_MS.last)) },
                enabled = trimMs < TRIM_RANGE_MS.last,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Trim 5 ms later")
            }
        }
        Slider(
            value = trimMs.toFloat(),
            onValueChange = { onTrimChange((it / 10f).toInt() * 10L) },
            valueRange = TRIM_RANGE_MS.first.toFloat()..TRIM_RANGE_MS.last.toFloat(),
        )
        Text(
            text = "If this device sounds late, drag right until the echo disappears",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SyncQualityChip(quality: SyncQuality, rttMs: Long?) {
    val (color, label) = when (quality) {
        SyncQuality.GOOD -> MaterialTheme.colorScheme.primaryContainer to
            "Sync: good${rttMs?.let { " · ${it}ms" } ?: ""}"
        SyncQuality.POOR -> MaterialTheme.colorScheme.errorContainer to
            "Network is jittery — sync may be off${rttMs?.let { " · ${it}ms" } ?: ""}"
        SyncQuality.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to "Measuring sync…"
    }
    Surface(color = color, shape = MaterialTheme.shapes.large) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
