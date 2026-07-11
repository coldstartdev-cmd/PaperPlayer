package com.jp.paperplayer.ui.party

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartySyncDebug
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.model.ui.SyncQuality
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

// `internal` (not private): app/src/screenshotTest/kotlin/.../ScreenPreviews.kt imports
// PartyHostPreview by name for the Compose Preview Screenshot Testing workflow that
// renders docs/screenshots/*.png — keep these names and visibility stable.

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
