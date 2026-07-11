package com.jp.paperplayer.ui.party

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.data.PartyMember
import com.jp.paperplayer.model.data.PartyMemberStatus
import com.jp.paperplayer.model.data.SyncHealth
import com.jp.paperplayer.model.ui.PartyUiState
import kotlin.math.abs

@Composable
internal fun ColumnScope.HostView(
    state: PartyUiState,
    onEndParty: () -> Unit,
    myEqRole: PartyEqRole = PartyEqRole.NONE,
    onSetMyEqRole: (PartyEqRole) -> Unit = {},
    onSetGuestEqRole: (String, PartyEqRole) -> Unit = { _, _ -> },
) {
    Text(
        text = state.partyName,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = "Guests join from Party mode on their device. Anything you play will play on every device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    state.startsInMs?.let { remaining ->
        StartCountdown(remainingMs = remaining)
    }
    HostSyncHealthChip(members = state.members)

    Text(
        text = "Speaker roles",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    Text(
        text = "With a few devices in the room, give each one a band to lean into.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = "This device", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        EqRoleRow(current = myEqRole, onSelect = onSetMyEqRole)
    }

    var showDashboard by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.members.isEmpty()) "No guests yet" else "${state.members.size} guest${if (state.members.size == 1) "" else "s"}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showDashboard = !showDashboard }) {
            Text(if (showDashboard) "Hide sync" else "Sync dashboard")
        }
    }
    LazyColumn(modifier = Modifier.weight(1f)) {
        if (showDashboard) {
            item(key = "host-self") {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "This device (host)",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                    }
                    Column(modifier = Modifier.padding(start = 32.dp)) {
                        DebugRow("Drift", state.syncDebug.lastDriftMs?.let { "$it ms" } ?: "—")
                        DebugRow("Speed", "${state.syncDebug.playbackSpeed}x")
                        DriftSparkline(history = state.syncDebug.driftHistory)
                    }
                }
            }
        }
        items(state.members, key = { it.id }) { member ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    if (member.status == PartyMemberStatus.PLAYING) {
                        SyncHealthDot(health = member.stats?.syncHealth ?: SyncHealth.UNKNOWN)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        text = member.status.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EqRoleRow(
                    current = member.eqRole,
                    onSelect = { role -> onSetGuestEqRole(member.id, role) },
                    modifier = Modifier.padding(start = 32.dp, top = 4.dp),
                )
                if (showDashboard) {
                    member.stats?.let { stats ->
                        Column(modifier = Modifier.padding(start = 32.dp)) {
                            val health = stats.syncHealth
                            DebugRow("Health", syncHealthLabel(health))
                            if (health == SyncHealth.FALLING_OUT_OF_SYNC) {
                                Text(
                                    text = "⚠ Drift is trending worse — check its WiFi signal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            DebugRow("Drift (guest-reported)", "${stats.driftMs} ms · ${formatTrim(stats.meanDriftMs)}")
                            DebugRow("Drift (host-verified)", "${stats.verifiedDriftMs} ms")
                            if (abs(stats.verifiedDriftMs - stats.driftMs) >= HOST_VERIFIED_MISMATCH_MS) {
                                Text(
                                    text = "Guest's own view disagrees with the host's — its last HOST_SYNC is stale",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            DebugRow("Trim", formatTrim(stats.latencyTrimMs))
                            DebugRow("Speed", "${stats.playbackSpeed}x")
                            DebugRow("Clock offset / RTT", "${stats.clockOffsetMs} ms · ${stats.rttMs} ms")
                            DebugRow(
                                "Corrections",
                                "${stats.nudgeCorrections} nudges · ${stats.seekCorrections} seeks" +
                                    if (stats.hostResyncCount > 0) " · ${stats.hostResyncCount} auto-resyncs" else "",
                            )
                            DebugRow(
                                "Network gaps",
                                "${stats.networkGapCount} (last ${stats.lastSampleGapMs} ms)",
                            )
                            if (stats.networkGapCount > 0) {
                                Text(
                                    text = "Delayed/dropped updates seen — likely WiFi, not audio drift",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (stats.driftHistory.size >= TRIM_SUGGESTION_MIN_SAMPLES &&
                                abs(stats.meanDriftMs) >= TRIM_SUGGESTION_THRESHOLD_MS
                            ) {
                                Text(
                                    text = "Suggested trim: ${formatTrim(stats.suggestedTrimMs)} (now ${formatTrim(stats.latencyTrimMs)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            DriftSparkline(history = stats.driftHistory)
                        }
                    } ?: Text(
                        text = "No telemetry yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 32.dp),
                    )
                }
            }
        }
    }

    OutlinedButton(
        onClick = onEndParty,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    ) {
        Text("End party")
    }
}

/** At-a-glance party-wide summary: worst health among currently-playing guests. */
@Composable
private fun HostSyncHealthChip(members: List<PartyMember>) {
    val playing = members.filter { it.status == PartyMemberStatus.PLAYING }
    if (playing.isEmpty()) return
    val healths = playing.map { it.stats?.syncHealth ?: SyncHealth.UNKNOWN }
    val worst = when {
        SyncHealth.FALLING_OUT_OF_SYNC in healths -> SyncHealth.FALLING_OUT_OF_SYNC
        SyncHealth.DRIFTING in healths -> SyncHealth.DRIFTING
        SyncHealth.UNKNOWN in healths -> SyncHealth.UNKNOWN
        else -> SyncHealth.IN_SYNC
    }
    val fallingCount = healths.count { it == SyncHealth.FALLING_OUT_OF_SYNC }
    val driftingCount = healths.count { it == SyncHealth.DRIFTING }
    val (color, label) = when (worst) {
        SyncHealth.IN_SYNC -> MaterialTheme.colorScheme.primaryContainer to
            "All ${playing.size} device${if (playing.size == 1) "" else "s"} in sync"
        SyncHealth.DRIFTING -> MaterialTheme.colorScheme.tertiaryContainer to
            "$driftingCount of ${playing.size} device${if (playing.size == 1) "" else "s"} drifting"
        SyncHealth.FALLING_OUT_OF_SYNC -> MaterialTheme.colorScheme.errorContainer to
            "$fallingCount device${if (fallingCount == 1) "" else "s"} falling out of sync"
        SyncHealth.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to "Measuring sync…"
    }
    Surface(
        color = color,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
