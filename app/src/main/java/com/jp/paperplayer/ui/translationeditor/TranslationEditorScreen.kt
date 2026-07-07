package com.jp.paperplayer.ui.translationeditor

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jp.paperplayer.model.ui.TranslationEditorState
import com.jp.paperplayer.ui.player.PlayerViewModel
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationEditorScreen(
    playerViewModel: PlayerViewModel,
    editorViewModel: TranslationEditorViewModel,
    onNavigateBack: () -> Unit,
) {
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val state by editorViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Populate editor once from player's pending translation data
    LaunchedEffect(Unit) {
        editorViewModel.setup(
            originalLines = playerState.lyrics,
            translatedLines = playerState.pendingEditorLines,
            targetLanguage = playerState.pendingEditorLanguage ?: "",
            songId = playerState.currentSong?.id ?: -1L,
        )
        playerViewModel.clearPendingEditor()
    }

    // Speech recognizer lifecycle
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context)
        else null
    }
    DisposableEffect(Unit) {
        onDispose { speechRecognizer?.destroy() }
    }

    // Microphone permission
    var hasAudioPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    fun stopListening() {
        speechRecognizer?.stopListening()
        editorViewModel.setListeningIndex(-1)
    }

    fun startListening(index: Int) {
        if (!hasAudioPermission) {
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        val recognizer = speechRecognizer ?: run {
            scope.launch { snackbarHostState.showSnackbar("Speech recognition not available") }
            return
        }
        editorViewModel.setListeningIndex(index)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { editorViewModel.setListeningIndex(-1) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) editorViewModel.updateLine(index, partial)
            }
            override fun onResults(bundle: Bundle?) {
                val result = bundle
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!result.isNullOrBlank()) editorViewModel.updateLine(index, result)
                editorViewModel.setListeningIndex(-1)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, state.targetLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    if (!state.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    TranslationEditorContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onUpdateLine = editorViewModel::updateLine,
        onMicClick = { index -> if (state.listeningIndex == index) stopListening() else startListening(index) },
        onSave = {
            stopListening()
            val lines = editorViewModel.toSavableLines()
            playerViewModel.saveTranslation(state.targetLanguage, lines)
            onNavigateBack()
        },
        onNavigateBack = {
            stopListening()
            onNavigateBack()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationEditorContent(
    state: TranslationEditorState,
    snackbarHostState: SnackbarHostState,
    onUpdateLine: (Int, String) -> Unit,
    onMicClick: (Int) -> Unit,
    onSave: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("Fix Translation", style = MaterialTheme.typography.titleMedium)
                        Text(
                            languageName(state.targetLanguage),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onSave) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(state.originalLines) { index, originalLine ->
                val editedText = state.editedLines.getOrElse(index) { "" }
                val isListening = state.listeningIndex == index

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    // Original line for reference
                    Text(
                        text = originalLine.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    // Editable translation + mic button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = editedText,
                            onValueChange = { onUpdateLine(index, it) },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { onMicClick(index) }) {
                            Icon(
                                imageVector = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (isListening) "Stop listening" else "Speak translation",
                                tint = if (isListening) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (index < state.originalLines.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TranslationEditorContentPreview() {
    PaperPlayerTheme {
        TranslationEditorContent(
            state = PreviewFixtures.translationEditorState,
            snackbarHostState = remember { SnackbarHostState() },
            onUpdateLine = { _, _ -> },
            onMicClick = {},
            onSave = {},
            onNavigateBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Listening")
@Composable
private fun TranslationEditorContentListeningPreview() {
    PaperPlayerTheme {
        TranslationEditorContent(
            state = PreviewFixtures.translationEditorState.copy(listeningIndex = 1),
            snackbarHostState = remember { SnackbarHostState() },
            onUpdateLine = { _, _ -> },
            onMicClick = {},
            onSave = {},
            onNavigateBack = {},
        )
    }
}

private fun languageName(code: String): String = LANGUAGE_DISPLAY_NAMES[code] ?: code.uppercase()

private val LANGUAGE_DISPLAY_NAMES = mapOf(
    "ar" to "Arabic", "bg" to "Bulgarian", "bn" to "Bengali", "cs" to "Czech",
    "da" to "Danish", "de" to "German", "el" to "Greek", "en" to "English",
    "es" to "Spanish", "fa" to "Persian", "fi" to "Finnish", "fr" to "French",
    "gu" to "Gujarati", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian",
    "hu" to "Hungarian", "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese",
    "kn" to "Kannada", "ko" to "Korean", "lt" to "Lithuanian", "lv" to "Latvian",
    "ml" to "Malayalam", "mr" to "Marathi", "ms" to "Malay", "nl" to "Dutch",
    "no" to "Norwegian", "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian",
    "ru" to "Russian", "sk" to "Slovak", "sv" to "Swedish", "sw" to "Swahili",
    "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tl" to "Tagalog",
    "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu", "vi" to "Vietnamese",
    "zh" to "Chinese",
)
