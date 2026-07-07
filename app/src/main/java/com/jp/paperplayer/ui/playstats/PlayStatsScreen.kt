package com.jp.paperplayer.ui.playstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.ui.player.PlayerViewModel
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.songlist.SongListViewModel
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayStatsScreen(
    songListViewModel: SongListViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
) {
    val songs by songListViewModel.songs.collectAsStateWithLifecycle()
    val playCounts by playerViewModel.playCounts.collectAsStateWithLifecycle()

    val sorted = songs.sortedByDescending { playCounts[it.id] ?: 0 }

    PlayStatsContent(
        sortedSongs = sorted,
        playCounts = playCounts,
        onNavigateBack = onNavigateBack,
        onSongClick = { song ->
            playerViewModel.play(songs, songs.indexOf(song))
            onNavigateToPlayer()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayStatsContent(
    sortedSongs: List<Song>,
    playCounts: Map<Long, Int>,
    onNavigateBack: () -> Unit,
    onSongClick: (Song) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Play Stats") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            itemsIndexed(sortedSongs, key = { _, song -> song.id }) { _, song ->
                PlayStatsRow(
                    song = song,
                    count = playCounts[song.id] ?: 0,
                    onClick = { onSongClick(song) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlayStatsContentPreview() {
    PaperPlayerTheme {
        PlayStatsContent(
            sortedSongs = PreviewFixtures.songs.sortedByDescending { PreviewFixtures.playCounts[it.id] ?: 0 },
            playCounts = PreviewFixtures.playCounts,
            onNavigateBack = {},
            onSongClick = {},
        )
    }
}

@Composable
private fun PlayStatsRow(song: Song, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (count > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = count.toString(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
