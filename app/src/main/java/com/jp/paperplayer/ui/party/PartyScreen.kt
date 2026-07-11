package com.jp.paperplayer.ui.party

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.data.PartyMember
import com.jp.paperplayer.model.data.PartyMemberStatus
import com.jp.paperplayer.model.data.SyncHealth
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartySyncDebug
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.model.ui.SyncQuality
import kotlin.math.abs
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

@Composable
fun PartyScreen(
    partyViewModel: PartyViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by partyViewModel.state.collectAsStateWithLifecycle()
    val deviceName by partyViewModel.deviceName.collectAsStateWithLifecycle()
    val latencyTrimMs by partyViewModel.latencyTrimMs.collectAsStateWithLifecycle()
    val isCalibrating by partyViewModel.isCalibrating.collectAsStateWithLifecycle()
    val calibrationMessage by partyViewModel.calibrationMessage.collectAsStateWithLifecycle()
    val suggestedTrimMs by partyViewModel.suggestedTrimMs.collectAsStateWithLifecycle()
    val myEqRole by partyViewModel.myEqRole.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) partyViewModel.calibrate()
    }

    // Discover while the chooser is visible; stop scanning when leaving it.
    DisposableEffect(state.role) {
        if (state.role == PartyRole.NONE) partyViewModel.startDiscovery()
        onDispose { if (state.role == PartyRole.NONE) partyViewModel.stopDiscovery() }
    }

    PartyContent(
        state = state,
        deviceName = deviceName,
        latencyTrimMs = latencyTrimMs,
        isCalibrating = isCalibrating,
        calibrationMessage = calibrationMessage,
        suggestedTrimMs = suggestedTrimMs,
        onApplySuggestedTrim = partyViewModel::applySuggestedTrim,
        onDismissSuggestedTrim = partyViewModel::dismissSuggestedTrim,
        onDeviceNameChange = partyViewModel::setDeviceName,
        onLatencyTrimChange = partyViewModel::setLatencyTrim,
        onCalibrate = {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) partyViewModel.calibrate()
            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onStartHosting = partyViewModel::startHosting,
        onJoin = partyViewModel::join,
        onLeave = partyViewModel::leaveParty,
        onNavigateBack = onNavigateBack,
        myEqRole = myEqRole,
        onSetMyEqRole = partyViewModel::setMyEqRole,
        onSetGuestEqRole = partyViewModel::setGuestEqRole,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartyContent(
    state: PartyUiState,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    onStartHosting: () -> Unit,
    onJoin: (DiscoveredParty) -> Unit,
    onLeave: () -> Unit,
    onNavigateBack: () -> Unit,
    latencyTrimMs: Long = 0L,
    onLatencyTrimChange: (Long) -> Unit = {},
    isCalibrating: Boolean = false,
    calibrationMessage: String? = null,
    suggestedTrimMs: Long? = null,
    onApplySuggestedTrim: () -> Unit = {},
    onDismissSuggestedTrim: () -> Unit = {},
    onCalibrate: () -> Unit = {},
    myEqRole: PartyEqRole = PartyEqRole.NONE,
    onSetMyEqRole: (PartyEqRole) -> Unit = {},
    onSetGuestEqRole: (String, PartyEqRole) -> Unit = { _, _ -> },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Party mode") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            when (state.role) {
                PartyRole.NONE -> ChooserView(
                    state = state,
                    deviceName = deviceName,
                    onDeviceNameChange = onDeviceNameChange,
                    onStartHosting = onStartHosting,
                    onJoin = onJoin,
                )
                PartyRole.HOST -> HostView(
                    state = state,
                    onEndParty = onLeave,
                    myEqRole = myEqRole,
                    onSetMyEqRole = onSetMyEqRole,
                    onSetGuestEqRole = onSetGuestEqRole,
                )
                PartyRole.GUEST -> GuestView(
                    state = state,
                    latencyTrimMs = latencyTrimMs,
                    isCalibrating = isCalibrating,
                    calibrationMessage = calibrationMessage,
                    suggestedTrimMs = suggestedTrimMs,
                    onApplySuggestedTrim = onApplySuggestedTrim,
                    onDismissSuggestedTrim = onDismissSuggestedTrim,
                    onLatencyTrimChange = onLatencyTrimChange,
                    onCalibrate = onCalibrate,
                    onLeave = onLeave,
                    myEqRole = myEqRole,
                )
            }
        }
    }
}

@Composable
private fun ChooserView(
    state: PartyUiState,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    onStartHosting: () -> Unit,
    onJoin: (DiscoveredParty) -> Unit,
) {
    OutlinedTextField(
        value = deviceName,
        onValueChange = onDeviceNameChange,
        label = { Text("Your device name") },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        singleLine = true,
    )
    Button(
        onClick = onStartHosting,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Host a party")
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Parties nearby",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (state.isDiscovering) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
    if (state.discovered.isEmpty()) {
        Text(
            text = "Searching on your WiFi network… make sure the host has started a party",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    } else {
        LazyColumn {
            items(state.discovered, key = { it.serviceName }) { party ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = party.serviceName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    OutlinedButton(onClick = { onJoin(party) }, enabled = !state.isJoining) {
                        if (state.isJoining) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Join")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.HostView(
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

@Composable
private fun GuestView(
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
                        text = "Downloading…",
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
private fun SyncDebugPanel(debug: PartySyncDebug) {
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
private fun DriftSparkline(history: List<Long>) {
    if (history.size < 2) return
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    Text(
        text = "Drift over the last ${history.size / 2} seconds",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(vertical = 4.dp),
    ) {
        val range = maxOf(history.maxOf { abs(it) }, 50L).toFloat()
        val midY = size.height / 2f
        drawLine(axisColor, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
        val stepX = size.width / (history.size - 1).coerceAtLeast(1)
        var previous: Offset? = null
        history.forEachIndexed { index, drift ->
            val point = Offset(
                x = index * stepX,
                y = midY - (drift / range) * (size.height / 2f - 2f),
            )
            previous?.let { drawLine(lineColor, it, point, strokeWidth = 3f) }
            previous = point
        }
    }
}

/** Off/Bass/Mid/Treble picker for the party-trick distributed-speaker EQ. */
@Composable
private fun EqRoleRow(current: PartyEqRole, onSelect: (PartyEqRole) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EQ_ROLE_OPTIONS.forEach { (role, label) ->
            val selected = role == current
            val padding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            if (selected) {
                Button(onClick = { onSelect(role) }, contentPadding = padding) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(onClick = { onSelect(role) }, contentPadding = padding) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private val EQ_ROLE_OPTIONS = listOf(
    PartyEqRole.NONE to "Off",
    PartyEqRole.BASS to "Bass",
    PartyEqRole.MID to "Mid",
    PartyEqRole.TREBLE to "Treble",
)

@Composable
private fun DebugRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatDebugSeconds(ms: Long): String = "%d.%01ds".format(ms / 1000, (ms % 1000) / 100)

private const val TRIM_STEP_MS = 5L
private val TRIM_RANGE_MS = -300L..300L

/** Only surface a trim suggestion once the bias is established and audible-adjacent. */
private const val TRIM_SUGGESTION_MIN_SAMPLES = 20
private const val TRIM_SUGGESTION_THRESHOLD_MS = 3L

/**
 * Gap between the guest's self-reported drift and the host's recomputed one
 * worth calling out — a sign the guest's own view is stale. Widened past the
 * two figures' theoretical noise floor (host-side verification is anchored
 * on receipt time minus half the measured RTT, an approximation with its own
 * few-ms error) so normal jitter doesn't read as staleness.
 */
private const val HOST_VERIFIED_MISMATCH_MS = 35L

private fun formatTrim(trimMs: Long): String = "${if (trimMs > 0) "+" else ""}$trimMs ms"

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
private fun StartCountdown(remainingMs: Long) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = "Starting in ${(remainingMs + 999) / 1000}s",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
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

/** Small colored dot next to a member's status, for a quick glance without opening the sync dashboard. */
@Composable
private fun SyncHealthDot(health: SyncHealth) {
    val color = when (health) {
        SyncHealth.IN_SYNC -> MaterialTheme.colorScheme.primary
        SyncHealth.DRIFTING -> MaterialTheme.colorScheme.tertiary
        SyncHealth.FALLING_OUT_OF_SYNC -> MaterialTheme.colorScheme.error
        SyncHealth.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
}

private fun syncHealthLabel(health: SyncHealth): String = when (health) {
    SyncHealth.IN_SYNC -> "In sync"
    SyncHealth.DRIFTING -> "Drifting"
    SyncHealth.FALLING_OUT_OF_SYNC -> "Falling out of sync"
    SyncHealth.UNKNOWN -> "Measuring…"
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

@Preview(showBackground = true)
@Composable
internal fun PartyChooserPreview() {
    PaperPlayerTheme {
        PartyContent(
            state = PartyUiState(isDiscovering = true, discovered = PreviewFixtures.discoveredParties),
            deviceName = "Pixel 7",
            onDeviceNameChange = {},
            onStartHosting = {},
            onJoin = {},
            onLeave = {},
            onNavigateBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Host roster")
@Composable
internal fun PartyHostPreview() {
    PaperPlayerTheme {
        PartyContent(
            state = PartyUiState(
                role = PartyRole.HOST,
                partyName = "Pixel 7's party",
                members = PreviewFixtures.partyMembers,
            ),
            deviceName = "Pixel 7",
            onDeviceNameChange = {},
            onStartHosting = {},
            onJoin = {},
            onLeave = {},
            onNavigateBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Sync debug panel")
@Composable
internal fun SyncDebugPanelPreview() {
    PaperPlayerTheme {
        SyncDebugPanel(
            debug = PartySyncDebug(
                clockOffsetMs = -142L,
                medianRttMs = 12L,
                lastDriftMs = 18L,
                playbackSpeed = 1f,
                expectedPositionMs = 63_400L,
                actualPositionMs = 63_418L,
                seekCorrections = 1,
                nudgeCorrections = 4,
                driftHistory = listOf(120L, 90L, 60L, 40L, 22L, 10L, 4L, -6L, -12L, -8L, -2L, 6L, 14L, 18L),
            )
        )
    }
}

@Preview(showBackground = true, name = "Guest connected")
@Composable
internal fun PartyGuestPreview() {
    PaperPlayerTheme {
        PartyContent(
            state = PartyUiState(
                role = PartyRole.GUEST,
                partyName = "Pixel 7's party",
                connectedHostName = "Pixel 7",
                syncQuality = SyncQuality.GOOD,
                rttMs = 6L,
                nowPlaying = "Midnight City — M83",
            ),
            deviceName = "Galaxy S23",
            onDeviceNameChange = {},
            onStartHosting = {},
            onJoin = {},
            onLeave = {},
            onNavigateBack = {},
        )
    }
}
