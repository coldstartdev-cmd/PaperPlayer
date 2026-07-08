package com.jp.paperplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jp.paperplayer.ui.components.MiniPlayer
import com.jp.paperplayer.ui.lyricseditor.LyricsEditorScreen
import com.jp.paperplayer.ui.lyricseditor.LyricsEditorViewModel
import com.jp.paperplayer.ui.party.PartyScreen
import com.jp.paperplayer.ui.party.PartyViewModel
import com.jp.paperplayer.ui.player.PlayerScreen
import com.jp.paperplayer.ui.player.PlayerViewModel
import com.jp.paperplayer.ui.playstats.PlayStatsScreen
import com.jp.paperplayer.ui.queue.QueueScreen
import com.jp.paperplayer.ui.settings.SettingsScreen
import com.jp.paperplayer.ui.songlist.SongListScreen
import com.jp.paperplayer.ui.songlist.SongListViewModel
import com.jp.paperplayer.ui.tageditor.TagEditorScreen
import com.jp.paperplayer.ui.tageditor.TagEditorViewModel
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import com.jp.paperplayer.ui.translationeditor.TranslationEditorScreen
import com.jp.paperplayer.ui.translationeditor.TranslationEditorViewModel

class MainActivity : ComponentActivity() {

    private val songListViewModel: SongListViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()
    private val lyricsEditorViewModel: LyricsEditorViewModel by viewModels()
    private val translationEditorViewModel: TranslationEditorViewModel by viewModels()
    private val tagEditorViewModel: TagEditorViewModel by viewModels()
    private val partyViewModel: PartyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaperPlayerTheme {
                val navController = rememberNavController()
                val playerState by playerViewModel.state.collectAsStateWithLifecycle()
                val currentEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentEntry?.destination?.route
                val showMiniPlayer = playerState.currentSong != null &&
                    currentRoute != "player" &&
                    currentRoute != "lyrics_editor"

                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (showMiniPlayer) {
                            MiniPlayer(
                                state = playerState,
                                onTogglePlayPause = playerViewModel::togglePlayPause,
                                onSkipNext = playerViewModel::skipNext,
                                onSkipPrevious = playerViewModel::skipPrevious,
                                onOpenPlayer = {
                                    navController.navigate("player") {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "song_list",
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable("song_list") {
                            SongListScreen(
                                songListViewModel = songListViewModel,
                                playerViewModel = playerViewModel,
                                onNavigateToPlayer = { navController.navigate("player") },
                                onNavigateToStats = { navController.navigate("play_stats") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToParty = { navController.navigate("party") },
                                onEditTags = { songId -> navController.navigate("tag_editor/$songId") },
                            )
                        }
                        composable("party") {
                            PartyScreen(
                                partyViewModel = partyViewModel,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                playerViewModel = playerViewModel,
                                songListViewModel = songListViewModel,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable("play_stats") {
                            PlayStatsScreen(
                                songListViewModel = songListViewModel,
                                playerViewModel = playerViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToPlayer = { navController.navigate("player") },
                            )
                        }
                        composable("player") {
                            PlayerScreen(
                                playerViewModel = playerViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToEditor = { navController.navigate("lyrics_editor") },
                                onNavigateToTranslationEditor = { navController.navigate("translation_editor") },
                                onNavigateToQueue = { navController.navigate("queue") },
                            )
                        }
                        composable("queue") {
                            QueueScreen(
                                playerViewModel = playerViewModel,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable("lyrics_editor") {
                            LyricsEditorScreen(
                                playerViewModel = playerViewModel,
                                editorViewModel = lyricsEditorViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("translation_editor") {
                            TranslationEditorScreen(
                                playerViewModel = playerViewModel,
                                editorViewModel = translationEditorViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            "tag_editor/{songId}",
                            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
                        ) { backStackEntry ->
                            val songId = backStackEntry.arguments?.getLong("songId") ?: -1L
                            val songs by songListViewModel.songs.collectAsStateWithLifecycle()
                            val song = songs.firstOrNull { it.id == songId }
                            if (song != null) {
                                TagEditorScreen(
                                    song = song,
                                    songListViewModel = songListViewModel,
                                    playerViewModel = playerViewModel,
                                    editorViewModel = tagEditorViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToLyricsEditor = { navController.navigate("lyrics_editor") },
                                )
                            } else {
                                LaunchedEffect(Unit) { navController.popBackStack() }
                            }
                        }
                    }
                }
            }
        }
    }
}
