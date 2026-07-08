package com.jp.paperplayer.ui.songlist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.ui.player.PlayerViewModel
import com.jp.paperplayer.ui.components.formatDuration
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

@Composable
fun SongListScreen(
    songListViewModel: SongListViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateToPlayer: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToParty: () -> Unit,
    onEditTags: (Long) -> Unit,
) {
    val songs by songListViewModel.songs.collectAsStateWithLifecycle()
    val isLoading by songListViewModel.isLoading.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val shuffleEnabled by playerViewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) songListViewModel.loadSongs()
    }

    LaunchedEffect(Unit) {
        if (hasPermission) songListViewModel.loadSongs()
        else permissionLauncher.launch(permission)
    }

    SongListContent(
        songs = songs,
        isLoading = isLoading,
        hasPermission = hasPermission,
        currentSongId = playerState.currentSong?.id,
        shuffleEnabled = shuffleEnabled,
        onShuffle = {
            playerViewModel.playShuffle(songs)
            onNavigateToPlayer()
        },
        onNavigateToStats = onNavigateToStats,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToParty = onNavigateToParty,
        onSongClick = { index ->
            playerViewModel.play(songs, index)
            onNavigateToPlayer()
        },
        onPlayNext = { song -> playerViewModel.playNext(song) },
        onEditTags = onEditTags,
    )
}

@Composable
private fun SongListContent(
    songs: List<Song>,
    isLoading: Boolean,
    hasPermission: Boolean,
    currentSongId: Long?,
    shuffleEnabled: Boolean,
    onShuffle: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToParty: () -> Unit = {},
    onSongClick: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    onEditTags: (Long) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .statusBarsPadding(),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "My Library",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = cs.onSurface,
                    letterSpacing = (-0.5).sp,
                    lineHeight = 27.6.sp,
                )
                if (songs.isNotEmpty()) {
                    Text(
                        text = "${songs.size} songs",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Box {
                HeaderIconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (songs.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Shuffle") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Shuffle,
                                    contentDescription = null,
                                    tint = if (shuffleEnabled) cs.primary else cs.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                showMenu = false
                                onShuffle()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Party mode") },
                        leadingIcon = {
                            Icon(Icons.Filled.Groups, contentDescription = null, tint = cs.onSurfaceVariant)
                        },
                        onClick = {
                            showMenu = false
                            onNavigateToParty()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Play stats") },
                        leadingIcon = {
                            Icon(Icons.Filled.BarChart, contentDescription = null, tint = cs.onSurfaceVariant)
                        },
                        onClick = {
                            showMenu = false
                            onNavigateToStats()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = {
                            Icon(Icons.Filled.Settings, contentDescription = null, tint = cs.onSurfaceVariant)
                        },
                        onClick = {
                            showMenu = false
                            onNavigateToSettings()
                        },
                    )
                }
            }
        }

        // ── Body ──────────────────────────────────────────────────────────────
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            !hasPermission -> Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Storage permission is required to scan music files.",
                    color = cs.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }

            songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No music found on this device.", color = cs.onSurfaceVariant, fontSize = 14.sp)
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        song = song,
                        isPlaying = song.id == currentSongId,
                        isOdd = index % 2 != 0,
                        onClick = { onSongClick(index) },
                        onPlayNext = { onPlayNext(song) },
                        onEditTags = { onEditTags(song.id) },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun SongListContentPreview() {
    PaperPlayerTheme {
        SongListContent(
            songs = PreviewFixtures.songs,
            isLoading = false,
            hasPermission = true,
            currentSongId = PreviewFixtures.song1.id,
            shuffleEnabled = false,
            onShuffle = {},
            onNavigateToStats = {},
            onSongClick = {},
            onPlayNext = {},
            onEditTags = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun SongListContentEmptyPreview() {
    PaperPlayerTheme {
        SongListContent(
            songs = emptyList(),
            isLoading = false,
            hasPermission = true,
            currentSongId = null,
            shuffleEnabled = false,
            onShuffle = {},
            onNavigateToStats = {},
            onSongClick = {},
            onPlayNext = {},
            onEditTags = {},
        )
    }
}

@Preview(showBackground = true, name = "Loading")
@Composable
private fun SongListContentLoadingPreview() {
    PaperPlayerTheme {
        SongListContent(
            songs = emptyList(),
            isLoading = true,
            hasPermission = true,
            currentSongId = null,
            shuffleEnabled = false,
            onShuffle = {},
            onNavigateToStats = {},
            onSongClick = {},
            onPlayNext = {},
            onEditTags = {},
        )
    }
}

@Preview(showBackground = true, name = "No permission")
@Composable
private fun SongListContentNoPermissionPreview() {
    PaperPlayerTheme {
        SongListContent(
            songs = emptyList(),
            isLoading = false,
            hasPermission = false,
            currentSongId = null,
            shuffleEnabled = false,
            onShuffle = {},
            onNavigateToStats = {},
            onSongClick = {},
            onPlayNext = {},
            onEditTags = {},
        )
    }
}

@Composable
private fun HeaderIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun SongRow(
    song: Song,
    isPlaying: Boolean,
    isOdd: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onEditTags: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var showRowMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isOdd) cs.surfaceContainerLow else cs.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(cs.primaryContainer),
        ) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Pause,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 14.sp,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isPlaying) cs.primary else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Text(
            text = formatDuration(song.duration),
            fontSize = 11.sp,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
            color = if (isPlaying) cs.primary else cs.outline,
        )

        Box {
            IconButton(onClick = { showRowMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Song options",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = showRowMenu, onDismissRequest = { showRowMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = cs.onSurfaceVariant)
                    },
                    onClick = {
                        showRowMenu = false
                        onPlayNext()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Edit tags") },
                    leadingIcon = {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = cs.onSurfaceVariant)
                    },
                    onClick = {
                        showRowMenu = false
                        onEditTags()
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SongRowPreview() {
    PaperPlayerTheme {
        Column {
            SongRow(song = PreviewFixtures.song1, isPlaying = true, isOdd = false, onClick = {}, onPlayNext = {}, onEditTags = {})
            SongRow(song = PreviewFixtures.song2, isPlaying = false, isOdd = true, onClick = {}, onPlayNext = {}, onEditTags = {})
        }
    }
}
