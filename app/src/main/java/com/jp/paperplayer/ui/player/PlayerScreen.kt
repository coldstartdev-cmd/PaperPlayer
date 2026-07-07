package com.jp.paperplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEditor: () -> Unit = {},
    onNavigateToTranslationEditor: () -> Unit = {},
    onNavigateToQueue: () -> Unit = {},
) {
    val state by playerViewModel.state.collectAsStateWithLifecycle()
    val shuffleEnabled by playerViewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val lyricsListState = rememberLazyListState()
    var isFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showLangPicker by remember { mutableStateOf(false) }
    var showAddLangPicker by remember { mutableStateOf(false) }
    val onTranslate = { showLangPicker = true }

    // Navigate to editor when ML Kit translation is ready
    LaunchedEffect(state.pendingEditorLanguage) {
        if (state.pendingEditorLanguage != null) {
            onNavigateToTranslationEditor()
        }
    }

    LaunchedEffect(state.translationError) {
        val err = state.translationError ?: return@LaunchedEffect
        scope.launch { snackbarHostState.showSnackbar(err) }
    }

    LaunchedEffect(state.currentLyricIndex) {
        val idx = state.currentLyricIndex
        if (idx >= 0 && state.lyrics.isNotEmpty()) {
            lyricsListState.animateScrollToItem(index = (idx - 2).coerceAtLeast(0))
        }
    }

    LaunchedEffect(controlsVisible, isFullscreen) {
        if (controlsVisible && isFullscreen) {
            delay(3_000)
            controlsVisible = false
        }
    }

    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(isFullscreen) {
        val activity = context as Activity
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Force landscape while in fullscreen — lyrics get more room and it matches the
            // "cinema mode" feel of other fullscreen media UIs. Reverted below and on dispose.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val safeNavigateBack: () -> Unit = {
        if (isFullscreen) {
            val activity = context as Activity
            WindowCompat.getInsetsController(activity.window, view)
                .show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onNavigateBack()
    }

    val onToggleShuffle = { playerViewModel.reshuffleQueue() }

    // Step 1: show saved translations + option to add new
    if (showLangPicker) {
        LangSwitcherDialog(
            activeLanguage = state.activeLanguage,
            availableLanguages = state.availableLanguages,
            onDismiss = { showLangPicker = false },
            onPickOriginal = {
                showLangPicker = false
                playerViewModel.switchToLanguage(null)
            },
            onPickSaved = { code ->
                showLangPicker = false
                playerViewModel.switchToLanguage(code)
            },
            onAddNew = {
                showLangPicker = false
                showAddLangPicker = true
            },
        )
    }

    // Step 2: pick a language to translate to
    if (showAddLangPicker) {
        LanguagePickerDialog(
            onDismiss = { showAddLangPicker = false },
            onLanguagePicked = { lang ->
                showAddLangPicker = false
                playerViewModel.requestTranslationForEditing(lang)
            },
        )
    }

    when {
        isFullscreen -> FullscreenLyricsLayout(
            state = state,
            lyricsListState = lyricsListState,
            controlsVisible = controlsVisible,
            shuffleEnabled = shuffleEnabled,
            snackbarHostState = snackbarHostState,
            onTap = { controlsVisible = !controlsVisible },
            onExitFullscreen = { isFullscreen = false; controlsVisible = true },
            onNavigateBack = safeNavigateBack,
            onToggleShuffle = onToggleShuffle,
            onTranslate = onTranslate,
            onSeek = playerViewModel::seekTo,
            onSkipPrevious = playerViewModel::skipPrevious,
            onTogglePlayPause = playerViewModel::togglePlayPause,
            onSkipNext = playerViewModel::skipNext,
        )
        isLandscape -> LandscapeLyricsLayout(
            state = state,
            lyricsListState = lyricsListState,
            shuffleEnabled = shuffleEnabled,
            snackbarHostState = snackbarHostState,
            onNavigateBack = safeNavigateBack,
            onEnterFullscreen = { isFullscreen = true; controlsVisible = true },
            onNavigateToEditor = onNavigateToEditor,
            onNavigateToQueue = onNavigateToQueue,
            onToggleShuffle = onToggleShuffle,
            onTranslate = onTranslate,
            onSkipPrevious = playerViewModel::skipPrevious,
            onTogglePlayPause = playerViewModel::togglePlayPause,
            onSkipNext = playerViewModel::skipNext,
        )
        else -> PortraitPlayerLayout(
            state = state,
            lyricsListState = lyricsListState,
            shuffleEnabled = shuffleEnabled,
            snackbarHostState = snackbarHostState,
            onNavigateBack = safeNavigateBack,
            onEnterFullscreen = { isFullscreen = true; controlsVisible = true },
            onNavigateToEditor = onNavigateToEditor,
            onNavigateToQueue = onNavigateToQueue,
            onToggleShuffle = onToggleShuffle,
            onTranslate = onTranslate,
            onSeek = playerViewModel::seekTo,
            onSkipPrevious = playerViewModel::skipPrevious,
            onTogglePlayPause = playerViewModel::togglePlayPause,
            onSkipNext = playerViewModel::skipNext,
        )
    }
}
