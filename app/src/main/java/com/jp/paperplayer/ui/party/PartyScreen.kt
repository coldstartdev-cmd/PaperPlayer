package com.jp.paperplayer.ui.party

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartyUiState

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
internal fun PartyContent(
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
