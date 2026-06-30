package com.jp.paperplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jp.paperplayer.ui.LyricsEditorScreen
import com.jp.paperplayer.ui.MiniPlayer
import com.jp.paperplayer.ui.PlayerScreen
import com.jp.paperplayer.ui.SongListScreen
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import com.jp.paperplayer.viewmodel.LyricsEditorViewModel
import com.jp.paperplayer.viewmodel.PlayerViewModel
import com.jp.paperplayer.viewmodel.SongListViewModel

class MainActivity : ComponentActivity() {

    private val songListViewModel: SongListViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()
    private val lyricsEditorViewModel: LyricsEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaperPlayerTheme {
                val navController = rememberNavController()
                val playerState by playerViewModel.state.collectAsStateWithLifecycle()
                val currentEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentEntry?.destination?.route
                val showMiniPlayer = playerState.currentSong != null && currentRoute != "player"

                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (showMiniPlayer) {
                            MiniPlayer(
                                state = playerState,
                                onTogglePlayPause = playerViewModel::togglePlayPause,
                                onTap = {
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
                                onNavigateToPlayer = { navController.navigate("player") }
                            )
                        }
                        composable("player") {
                            PlayerScreen(
                                playerViewModel = playerViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToEditor = { navController.navigate("lyrics_editor") }
                            )
                        }
                        composable("lyrics_editor") {
                            LyricsEditorScreen(
                                playerViewModel = playerViewModel,
                                editorViewModel = lyricsEditorViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
