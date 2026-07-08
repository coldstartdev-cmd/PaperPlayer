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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.PartyMember
import com.jp.paperplayer.model.data.PartyMemberStatus
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.model.ui.SyncQuality
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

@Composable
fun PartyScreen(
    partyViewModel: PartyViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by partyViewModel.state.collectAsStateWithLifecycle()
    val deviceName by partyViewModel.deviceName.collectAsStateWithLifecycle()

    // Discover while the chooser is visible; stop scanning when leaving it.
    DisposableEffect(state.role) {
        if (state.role == PartyRole.NONE) partyViewModel.startDiscovery()
        onDispose { if (state.role == PartyRole.NONE) partyViewModel.stopDiscovery() }
    }

    PartyContent(
        state = state,
        deviceName = deviceName,
        onDeviceNameChange = partyViewModel::setDeviceName,
        onStartHosting = partyViewModel::startHosting,
        onJoin = partyViewModel::join,
        onLeave = partyViewModel::leaveParty,
        onNavigateBack = onNavigateBack,
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
                PartyRole.HOST -> HostView(state = state, onEndParty = onLeave)
                PartyRole.GUEST -> GuestView(state = state, onLeave = onLeave)
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
private fun HostView(state: PartyUiState, onEndParty: () -> Unit) {
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

    Text(
        text = if (state.members.isEmpty()) "No guests yet" else "${state.members.size} guest${if (state.members.size == 1) "" else "s"}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    LazyColumn(modifier = Modifier.weight(1f)) {
        items(state.members, key = { it.id }) { member ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Text(
                    text = member.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun GuestView(state: PartyUiState, onLeave: () -> Unit) {
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
        Spacer(Modifier.height(16.dp))
        SyncQualityChip(quality = state.syncQuality, rttMs = state.rttMs)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onLeave) {
            Text("Leave party")
        }
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
