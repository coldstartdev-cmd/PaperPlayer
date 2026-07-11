package com.jp.paperplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.data.PartyFileTransferMode
import com.jp.paperplayer.data.ShuffleStrategy
import com.jp.paperplayer.model.data.MusicFolder
import com.jp.paperplayer.ui.player.PlayerViewModel
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.songlist.SongListViewModel
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    playerViewModel: PlayerViewModel,
    songListViewModel: SongListViewModel,
    onNavigateBack: () -> Unit,
) {
    val strategy by playerViewModel.shuffleStrategy.collectAsStateWithLifecycle()
    val fileTransferMode by playerViewModel.partyFileTransferMode.collectAsStateWithLifecycle()
    val musicFolders by songListViewModel.musicFolders.collectAsStateWithLifecycle()
    SettingsContent(
        shuffleStrategy = strategy,
        partyFileTransferMode = fileTransferMode,
        musicFolders = musicFolders,
        onNavigateBack = onNavigateBack,
        onShuffleStrategyChange = playerViewModel::setShuffleStrategy,
        onPartyFileTransferModeChange = playerViewModel::setPartyFileTransferMode,
        onFolderExcludedChange = songListViewModel::setFolderExcluded,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    shuffleStrategy: ShuffleStrategy,
    partyFileTransferMode: PartyFileTransferMode,
    musicFolders: List<MusicFolder>,
    onNavigateBack: () -> Unit,
    onShuffleStrategyChange: (ShuffleStrategy) -> Unit,
    onPartyFileTransferModeChange: (PartyFileTransferMode) -> Unit,
    onFolderExcludedChange: (path: String, excluded: Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Shuffle strategy",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            RadioSettingRow(
                title = "Smart shuffle",
                description = "Favors songs you haven't played as much",
                selected = shuffleStrategy == ShuffleStrategy.SMART,
                onClick = { onShuffleStrategyChange(ShuffleStrategy.SMART) },
            )
            RadioSettingRow(
                title = "Random shuffle",
                description = "Plain random order, no weighting",
                selected = shuffleStrategy == ShuffleStrategy.RANDOM,
                onClick = { onShuffleStrategyChange(ShuffleStrategy.RANDOM) },
            )

            Text(
                text = "Party mode",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                text = "How to get a song's audio from the host when you join as a guest",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            RadioSettingRow(
                title = "Download before playing",
                description = "More reliable, uses local storage",
                selected = partyFileTransferMode == PartyFileTransferMode.DOWNLOAD,
                onClick = { onPartyFileTransferModeChange(PartyFileTransferMode.DOWNLOAD) },
            )
            RadioSettingRow(
                title = "Stream while playing",
                description = "No local storage used, needs a steady WiFi connection",
                selected = partyFileTransferMode == PartyFileTransferMode.STREAM,
                onClick = { onPartyFileTransferModeChange(PartyFileTransferMode.STREAM) },
            )

            Text(
                text = "Music folders",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (musicFolders.isEmpty()) {
                Text(
                    text = "Folders appear here once your library has been scanned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    text = "Turn a folder off to hide its songs from your library",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                musicFolders.forEach { folder ->
                    MusicFolderRow(
                        folder = folder,
                        onExcludedChange = { excluded -> onFolderExcludedChange(folder.path, excluded) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicFolderRow(
    folder: MusicFolder,
    onExcludedChange: (excluded: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${folder.name}  ·  ${folder.songCount} ${if (folder.songCount == 1) "song" else "songs"}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folder.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = !folder.excluded,
            onCheckedChange = { included -> onExcludedChange(!included) },
        )
    }
}

@Composable
private fun RadioSettingRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun SettingsContentPreview() {
    PaperPlayerTheme {
        SettingsContent(
            shuffleStrategy = ShuffleStrategy.SMART,
            partyFileTransferMode = PartyFileTransferMode.DOWNLOAD,
            musicFolders = PreviewFixtures.musicFolders,
            onNavigateBack = {},
            onShuffleStrategyChange = {},
            onPartyFileTransferModeChange = {},
            onFolderExcludedChange = { _, _ -> },
        )
    }
}
