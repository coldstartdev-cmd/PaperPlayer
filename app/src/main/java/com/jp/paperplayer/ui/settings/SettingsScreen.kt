package com.jp.paperplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.data.ShuffleStrategy
import com.jp.paperplayer.ui.player.PlayerViewModel
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
) {
    val strategy by playerViewModel.shuffleStrategy.collectAsStateWithLifecycle()
    SettingsContent(
        shuffleStrategy = strategy,
        onNavigateBack = onNavigateBack,
        onShuffleStrategyChange = playerViewModel::setShuffleStrategy,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    shuffleStrategy: ShuffleStrategy,
    onNavigateBack: () -> Unit,
    onShuffleStrategyChange: (ShuffleStrategy) -> Unit,
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
        Column(modifier = Modifier.padding(padding)) {
            Text(
                text = "Shuffle strategy",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            ShuffleStrategyRow(
                title = "Smart shuffle",
                description = "Favors songs you haven't played as much",
                selected = shuffleStrategy == ShuffleStrategy.SMART,
                onClick = { onShuffleStrategyChange(ShuffleStrategy.SMART) },
            )
            ShuffleStrategyRow(
                title = "Random shuffle",
                description = "Plain random order, no weighting",
                selected = shuffleStrategy == ShuffleStrategy.RANDOM,
                onClick = { onShuffleStrategyChange(ShuffleStrategy.RANDOM) },
            )
        }
    }
}

@Composable
private fun ShuffleStrategyRow(
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
private fun SettingsContentPreview() {
    PaperPlayerTheme {
        SettingsContent(
            shuffleStrategy = ShuffleStrategy.SMART,
            onNavigateBack = {},
            onShuffleStrategyChange = {},
        )
    }
}
