package com.jp.paperplayer.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.mlkit.nl.translate.TranslateLanguage
import com.jp.paperplayer.viewmodel.PlayerState
import com.jp.paperplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEditor: () -> Unit = {},
) {
    val state by playerViewModel.state.collectAsStateWithLifecycle()
    val shuffleEnabled by playerViewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val lyricsListState = rememberLazyListState()
    var isFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var fontSize by remember { mutableFloatStateOf(17f) }
    var showFontSizeSlider by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val targetLanguage = remember {
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH
    }
    val onTranslate = { playerViewModel.toggleTranslation(targetLanguage) }

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
        val window = (context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val safeNavigateBack: () -> Unit = {
        if (isFullscreen) {
            WindowCompat.getInsetsController((context as Activity).window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        onNavigateBack()
    }

    val onToggleShuffle = { playerViewModel.setShuffleEnabled(!shuffleEnabled) }

    when {
        isFullscreen -> FullscreenLyricsLayout(
            state = state,
            lyricsListState = lyricsListState,
            playerViewModel = playerViewModel,
            controlsVisible = controlsVisible,
            fontSize = fontSize,
            shuffleEnabled = shuffleEnabled,
            snackbarHostState = snackbarHostState,
            onFontSizeChange = { fontSize = it },
            onTap = { controlsVisible = !controlsVisible },
            onExitFullscreen = { isFullscreen = false; controlsVisible = true },
            onNavigateBack = safeNavigateBack,
            onToggleShuffle = onToggleShuffle,
            onTranslate = onTranslate,
        )
        isLandscape -> LandscapeLyricsLayout(
            state = state,
            lyricsListState = lyricsListState,
            playerViewModel = playerViewModel,
            fontSize = fontSize,
            showFontSizeSlider = showFontSizeSlider,
            shuffleEnabled = shuffleEnabled,
            snackbarHostState = snackbarHostState,
            onToggleFontSizeSlider = { showFontSizeSlider = !showFontSizeSlider },
            onFontSizeChange = { fontSize = it },
            onNavigateBack = safeNavigateBack,
            onEnterFullscreen = { isFullscreen = true; controlsVisible = true },
            onNavigateToEditor = onNavigateToEditor,
            onToggleShuffle = onToggleShuffle,
            onTranslate = onTranslate,
        )
        else -> PortraitPlayerLayout(
            state = state,
            lyricsListState = lyricsListState,
            playerViewModel = playerViewModel,
            fontSize = fontSize,
            showFontSizeSlider = showFontSizeSlider,
            shuffleEnabled = shuffleEnabled,
            snackbarHostState = snackbarHostState,
            onToggleFontSizeSlider = { showFontSizeSlider = !showFontSizeSlider },
            onFontSizeChange = { fontSize = it },
            onNavigateBack = safeNavigateBack,
            onEnterFullscreen = { isFullscreen = true; controlsVisible = true },
            onNavigateToEditor = onNavigateToEditor,
            onToggleShuffle = onToggleShuffle,
            onTranslate = onTranslate,
        )
    }
}

// ── Fullscreen immersive lyrics — tap anywhere to toggle controls overlay ─────

@Composable
private fun FullscreenLyricsLayout(
    state: PlayerState,
    lyricsListState: LazyListState,
    playerViewModel: PlayerViewModel,
    controlsVisible: Boolean,
    fontSize: Float,
    shuffleEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    onFontSizeChange: (Float) -> Unit,
    onTap: () -> Unit,
    onExitFullscreen: () -> Unit,
    onNavigateBack: () -> Unit,
    onToggleShuffle: () -> Unit,
    onTranslate: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val displayLyrics = if (state.showTranslation) state.translatedLyrics ?: state.lyrics else state.lyrics

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
    ) {
        if (displayLyrics.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.isTranslating) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = "No lyrics available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                state = lyricsListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 80.dp),
            ) {
                itemsIndexed(displayLyrics) { index, line ->
                    LyricLineItem(
                        text = line.text,
                        index = index,
                        currentIndex = state.currentLyricIndex,
                        fontSize = fontSize,
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        // Top overlay: back | song title | exit fullscreen
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(background.copy(alpha = 0.88f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.currentSong?.title ?: "—",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.currentSong?.artist ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                    if (state.isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp))
                    } else {
                        IconButton(onClick = onTranslate) {
                            Icon(
                                Icons.Filled.GTranslate,
                                contentDescription = "Translate",
                                tint = if (state.showTranslation) MaterialTheme.colorScheme.primary
                                       else LocalContentColor.current,
                            )
                        }
                    }
                    IconButton(onClick = onExitFullscreen) {
                        Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit fullscreen")
                    }
                }
            }
        }

        // Bottom overlay: font size slider + seek bar + playback controls
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            var dragPosition by remember { mutableStateOf<Float?>(null) }
            val sliderValue = dragPosition
                ?: if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, background.copy(alpha = 0.88f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FontSizeSliderRow(fontSize = fontSize, onFontSizeChange = onFontSizeChange)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = sliderValue,
                        onValueChange = { dragPosition = it },
                        onValueChangeFinished = {
                            dragPosition?.let { f ->
                                playerViewModel.seekTo((f * state.durationMs).toLong())
                            }
                            dragPosition = null
                        },
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            formatDuration(state.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatDuration(state.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.size(28.dp),
                            tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { playerViewModel.skipPrevious() }) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = { playerViewModel.togglePlayPause() },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = { playerViewModel.skipNext() }) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Landscape: slim top bar + collapsible font size slider + lyrics ───────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LandscapeLyricsLayout(
    state: PlayerState,
    lyricsListState: LazyListState,
    playerViewModel: PlayerViewModel,
    fontSize: Float,
    showFontSizeSlider: Boolean,
    shuffleEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    onToggleFontSizeSlider: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onNavigateBack: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onNavigateToEditor: () -> Unit,
    onToggleShuffle: () -> Unit,
    onTranslate: () -> Unit,
) {
    val displayLyrics = if (state.showTranslation) state.translatedLyrics ?: state.lyrics else state.lyrics

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.currentSong?.title ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.currentSong?.artist ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { playerViewModel.skipPrevious() }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    IconButton(onClick = { playerViewModel.togglePlayPause() }) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = { playerViewModel.skipNext() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                    IconButton(onClick = onToggleFontSizeSlider) {
                        Icon(Icons.Filled.FormatSize, contentDescription = "Font size")
                    }
                    IconButton(onClick = onEnterFullscreen) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen")
                    }
                    if (state.isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        IconButton(onClick = onTranslate) {
                            Icon(
                                Icons.Filled.GTranslate,
                                contentDescription = "Translate",
                                tint = if (state.showTranslation) MaterialTheme.colorScheme.primary
                                       else LocalContentColor.current,
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToEditor) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit lyrics")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedVisibility(visible = showFontSizeSlider) {
                FontSizeSliderRow(
                    fontSize = fontSize,
                    onFontSizeChange = onFontSizeChange,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (displayLyrics.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.isTranslating) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = "No lyrics available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = lyricsListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    itemsIndexed(displayLyrics) { index, line ->
                        LyricLineItem(
                            text = line.text,
                            index = index,
                            currentIndex = state.currentLyricIndex,
                            fontSize = fontSize,
                        )
                    }
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}

// ── Portrait: full-screen album art; lyrics blur the background when available ─

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortraitPlayerLayout(
    state: PlayerState,
    lyricsListState: LazyListState,
    playerViewModel: PlayerViewModel,
    fontSize: Float,
    showFontSizeSlider: Boolean,
    shuffleEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    onToggleFontSizeSlider: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onNavigateBack: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onNavigateToEditor: () -> Unit,
    onToggleShuffle: () -> Unit,
    onTranslate: () -> Unit,
) {
    val displayLyrics = if (state.showTranslation) state.translatedLyrics ?: state.lyrics else state.lyrics
    val hasLyrics = displayLyrics.isNotEmpty()
    val onContent = if (hasLyrics) Color.White else MaterialTheme.colorScheme.onSurface
    val onContentVariant = if (hasLyrics) Color.White.copy(alpha = 0.7f)
                           else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background: album art (blurred when lyrics are shown) ────────────
        AsyncImage(
            model = state.currentSong?.albumArtUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(if (hasLyrics) Modifier.blur(28.dp) else Modifier),
            contentScale = ContentScale.Crop,
        )

        // Dark scrim so lyrics and controls are readable over the blurred art
        if (hasLyrics) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
            )
        }

        // ── Foreground column ────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onContent)
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleFontSizeSlider) {
                    Icon(Icons.Filled.FormatSize, contentDescription = "Font size", tint = onContent)
                }
                IconButton(onClick = onEnterFullscreen) {
                    Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen", tint = onContent)
                }
                if (state.isTranslating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = onContent)
                } else {
                    IconButton(onClick = onTranslate) {
                        Icon(
                            Icons.Filled.GTranslate,
                            contentDescription = "Translate",
                            tint = if (state.showTranslation) MaterialTheme.colorScheme.primary else onContent,
                        )
                    }
                }
                IconButton(onClick = onNavigateToEditor) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit lyrics", tint = onContent)
                }
            }

            // Song title + artist (below the top bar)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Text(
                    text = state.currentSong?.title ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.currentSong?.artist ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContentVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // Lyrics scroll in the middle, spacer keeps controls at bottom when empty
            if (state.isTranslating) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (displayLyrics.isNotEmpty()) {
                LazyColumn(
                    state = lyricsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    itemsIndexed(displayLyrics) { index, line ->
                        LyricLineItem(
                            text = line.text,
                            index = index,
                            currentIndex = state.currentLyricIndex,
                            fontSize = fontSize,
                            overrideColors = true,
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Controls pinned at bottom ────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                AnimatedVisibility(visible = showFontSizeSlider) {
                    FontSizeSliderRow(
                        fontSize = fontSize,
                        onFontSizeChange = onFontSizeChange,
                        modifier = Modifier.padding(bottom = 4.dp),
                        tint = onContent,
                    )
                }

                var dragPosition by remember { mutableStateOf<Float?>(null) }
                val sliderValue = dragPosition
                    ?: if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f

                Slider(
                    value = sliderValue,
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        dragPosition?.let { fraction ->
                            playerViewModel.seekTo((fraction * state.durationMs).toLong())
                        }
                        dragPosition = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall, color = onContentVariant)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall, color = onContentVariant)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.size(28.dp),
                            tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else onContentVariant,
                        )
                    }
                    IconButton(onClick = { playerViewModel.skipPrevious() }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp), tint = onContent)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = { playerViewModel.togglePlayPause() },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(onClick = { playerViewModel.skipNext() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp), tint = onContent)
                    }
                }
            }
        }
    }
}

// ── Font size slider row: small "A" — slider — big "A" ───────────────────────

@Composable
private fun FontSizeSliderRow(
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "A", fontSize = 12.sp, color = tint)
        Slider(
            value = fontSize,
            onValueChange = onFontSizeChange,
            valueRange = 12f..28f,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(text = "A", fontSize = 22.sp, color = tint)
    }
}

// ── Shared lyric line: uniform font size, opacity fades with distance ─────────

@Composable
private fun LyricLineItem(
    text: String,
    index: Int,
    currentIndex: Int,
    fontSize: Float,
    overrideColors: Boolean = false,
) {
    val distance = if (currentIndex < 0) Int.MAX_VALUE else abs(index - currentIndex)
    val isActive = distance == 0

    val itemAlpha by animateFloatAsState(
        targetValue = when {
            distance == 0 -> 1.00f
            distance == 1 -> 0.70f
            distance == 2 -> 0.45f
            else -> 0.25f
        },
        label = "alpha_$index",
    )

    val activeColor = if (overrideColors) Color.White else MaterialTheme.colorScheme.primary
    val inactiveColor = if (overrideColors) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    val color by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        label = "color_$index",
    )

    Text(
        text = text,
        fontSize = fontSize.sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(itemAlpha),
    )
}

// ── Mini-player: persistent bar shown on all screens except the player ────────

@Composable
fun MiniPlayer(
    state: PlayerState,
    onTogglePlayPause: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = state.currentSong?.albumArtUri,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.currentSong?.title ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.currentSong?.artist ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
        }
    }
}
