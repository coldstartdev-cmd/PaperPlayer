package com.jp.paperplayer.ui.lyricseditor

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Abc
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jp.paperplayer.model.data.LyricLine
import com.jp.paperplayer.model.ui.EditorLine
import com.jp.paperplayer.model.ui.LyricsEditorState
import com.jp.paperplayer.model.ui.PlayerState
import com.jp.paperplayer.translation.isTransliterationSupported
import com.jp.paperplayer.translation.translateLyrics
import com.jp.paperplayer.translation.transliterateToLatin
import com.jp.paperplayer.ui.player.LanguagePickerDialog
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Phase 3: fine-tune individual timestamps ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinetuningPhase(
    editorState: LyricsEditorState,
    playerState: PlayerState,
    filePath: String?,
    songUri: Uri?,
    snackbarHostState: SnackbarHostState,
    onAdjust: (index: Int, deltaMs: Long) -> Unit,
    onSetTimestamp: (index: Int, timeMs: Long) -> Unit,
    onEditText: (index: Int, text: String) -> Unit,
    onRemoveLine: (index: Int) -> Unit,
    onInsertLineAfter: (index: Int) -> Unit,
    onBatchAdjust: (indices: Set<Int>, deltaMs: Long) -> Unit,
    onBackToEditing: () -> Unit,
    onSave: (filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) -> Unit,
    onSaveSuccess: () -> Unit,
    onPermissionNeeded: (Uri, retry: () -> Unit) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPause: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lines = editorState.lines

    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    var batchDeltaText by remember { mutableStateOf("100") }
    var targetLanguage by remember { mutableStateOf<String?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var dragAnchorIndex by remember { mutableStateOf(-1) }
    val itemWindowTops = remember { mutableStateMapOf<Int, Float>() }
    var dragCurrentWindowY by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(lines.size) { itemWindowTops.clear() }

    LaunchedEffect(dragCurrentWindowY) {
        val y = dragCurrentWindowY ?: return@LaunchedEffect
        val anchor = dragAnchorIndex.takeIf { it >= 0 } ?: return@LaunchedEffect
        val hit = itemWindowTops.entries
            .filter { it.value <= y }
            .maxByOrNull { it.value }?.key ?: anchor
        selectedIndices = (minOf(anchor, hit)..maxOf(anchor, hit)).toSet()
    }

    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 80.dp.toPx() }
    val maxScrollPxPerFrame = with(density) { 10.dp.toPx() }
    var boxWindowTop by remember { mutableStateOf(0f) }
    var boxWindowBottom by remember { mutableStateOf(0f) }

    // While a drag is active and the finger is within the edge threshold,
    // scroll the list and extend the selection into newly-visible items.
    LaunchedEffect(dragAnchorIndex >= 0) {
        while (dragAnchorIndex >= 0) {
            val y = dragCurrentWindowY
            if (y != null) {
                val speed = when {
                    y < boxWindowTop + edgeThresholdPx ->
                        -maxScrollPxPerFrame * ((boxWindowTop + edgeThresholdPx - y) / edgeThresholdPx).coerceIn(0f, 1f)
                    y > boxWindowBottom - edgeThresholdPx ->
                        maxScrollPxPerFrame * ((y - (boxWindowBottom - edgeThresholdPx)) / edgeThresholdPx).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (speed != 0f) {
                    listState.scrollBy(speed)
                    val anchor = dragAnchorIndex
                    if (anchor >= 0) {
                        val hit = itemWindowTops.entries
                            .filter { it.value <= y }
                            .maxByOrNull { it.value }?.key ?: anchor
                        selectedIndices = (minOf(anchor, hit)..maxOf(anchor, hit)).toSet()
                    }
                }
            }
            delay(16L)
        }
    }

    var segmentEndMs by remember { mutableStateOf<Long?>(null) }
    var playingIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(playerState.positionMs) {
        // Auto-pause at the end of the previewed segment
        val end = segmentEndMs
        if (end != null && playerState.positionMs >= end) {
            onPause()
            segmentEndMs = null
            playingIndex = null
        }
        // Prevent auto-advance to next song when the track ends
        val dur = playerState.durationMs
        if (dur > 1_000 && playerState.isPlaying && playerState.positionMs >= dur - 500) {
            onPause()
        }
    }

    // If playback stops for any reason (global pause, segment end), clear segment state
    LaunchedEffect(playerState.isPlaying) {
        if (!playerState.isPlaying) {
            playingIndex = null
            segmentEndMs = null
        }
    }

    fun doSave() {
        val path = filePath
        val uri = songUri
        if (path.isNullOrBlank() || uri == null) {
            scope.launch { snackbarHostState.showSnackbar("No file path available") }
            return
        }
        onSave(path) { success, permDenied ->
            when {
                success -> onSaveSuccess()
                permDenied -> onPermissionNeeded(uri) {
                    onSave(path) { s, _ ->
                        if (s) onSaveSuccess()
                        else scope.launch { snackbarHostState.showSnackbar("Save failed") }
                    }
                }
                else -> scope.launch { snackbarHostState.showSnackbar("Save failed — check file permissions") }
            }
        }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            onDismiss = { showLanguagePicker = false },
            onLanguagePicked = { lang ->
                targetLanguage = lang
                showLanguagePicker = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fine-tune Timestamps") },
                navigationIcon = {
                    IconButton(onClick = onBackToEditing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (canUndo) {
                        IconButton(onClick = onUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                    }
                    when {
                        editorState.isSaving -> CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                        else -> {
                            IconButton(onClick = ::doSave) {
                                Icon(Icons.Filled.Check, contentDescription = "Save")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CompactPlayer(
                playerState = playerState,
                onSkipPrevious = onSkipPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onSeek = onSeek,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            HorizontalDivider()

            // Batch-adjust bar
            BatchAdjustBar(
                lineCount = lines.size,
                selectedIndices = selectedIndices,
                batchDeltaText = batchDeltaText,
                targetLanguage = targetLanguage,
                onBatchDeltaChange = { batchDeltaText = it.filter(Char::isDigit).take(5) },
                onToggleSelectAll = {
                    selectedIndices = if (selectedIndices.size == lines.size) emptySet()
                                     else lines.indices.toSet()
                },
                onBatchApply = { delta ->
                    onBatchAdjust(selectedIndices, delta)
                    selectedIndices = emptySet()
                },
                onPickLanguage = { showLanguagePicker = true },
            )
            HorizontalDivider()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        boxWindowTop = coords.positionInWindow().y
                        boxWindowBottom = boxWindowTop + coords.size.height.toFloat()
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val windowY = boxWindowTop + offset.y
                                val hit = itemWindowTops.entries
                                    .filter { it.value <= windowY }
                                    .maxByOrNull { it.value }?.key ?: -1
                                if (hit >= 0) {
                                    dragAnchorIndex = hit
                                    selectedIndices = setOf(hit)
                                    dragCurrentWindowY = windowY
                                }
                            },
                            onDrag = { change, _ ->
                                if (dragAnchorIndex >= 0) {
                                    dragCurrentWindowY = boxWindowTop + change.position.y
                                }
                            },
                            onDragEnd = {
                                dragCurrentWindowY = null
                                dragAnchorIndex = -1
                            },
                            onDragCancel = {
                                dragCurrentWindowY = null
                                dragAnchorIndex = -1
                            },
                        )
                    },
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { index, line ->
                        FinetuneLineItem(
                            line = line,
                            isSelected = index in selectedIndices,
                            selectionMode = selectedIndices.isNotEmpty(),
                            isSegmentPlaying = index == playingIndex && playerState.isPlaying,
                            targetLanguage = targetLanguage,
                            onToggleSelect = {
                                selectedIndices = if (index in selectedIndices) selectedIndices - index
                                                else selectedIndices + index
                            },
                            onPlaySegment = {
                                onSeek(line.timeMs ?: 0L)
                                if (!playerState.isPlaying) onTogglePlayPause()
                                segmentEndMs = lines.getOrNull(index + 1)?.timeMs
                                playingIndex = index
                            },
                            onStopSegment = {
                                onPause()
                                segmentEndMs = null
                                playingIndex = null
                            },
                            onAdjust = { delta -> onAdjust(index, delta) },
                            onSetTimestamp = { ms -> onSetTimestamp(index, ms) },
                            onEditText = { text -> onEditText(index, text) },
                            onRemove = {
                                onRemoveLine(index)
                                selectedIndices = emptySet()
                            },
                            onPositioned = { windowTop -> itemWindowTops[index] = windowTop },
                        )
                        InsertLineDivider {
                            onInsertLineAfter(index)
                            selectedIndices = emptySet()
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FinetuningPhasePreview() {
    PaperPlayerTheme {
        FinetuningPhase(
            editorState = LyricsEditorState(lines = PreviewFixtures.editorLines),
            playerState = PreviewFixtures.playerState,
            filePath = PreviewFixtures.song1.filePath,
            songUri = PreviewFixtures.song1.uri,
            snackbarHostState = remember { SnackbarHostState() },
            onAdjust = { _, _ -> },
            onSetTimestamp = { _, _ -> },
            onEditText = { _, _ -> },
            onRemoveLine = {},
            onInsertLineAfter = {},
            onBatchAdjust = { _, _ -> },
            onBackToEditing = {},
            onSave = { _, _ -> },
            onSaveSuccess = {},
            onPermissionNeeded = { _, _ -> },
            canUndo = true,
            onUndo = {},
            onSkipPrevious = {},
            onSkipNext = {},
            onTogglePlayPause = {},
            onSeek = {},
            onPause = {},
        )
    }
}

// ── Batch-adjust bar ──────────────────────────────────────────────────────────

@Composable
private fun BatchAdjustBar(
    lineCount: Int,
    selectedIndices: Set<Int>,
    batchDeltaText: String,
    targetLanguage: String?,
    onBatchDeltaChange: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onBatchApply: (Long) -> Unit,
    onPickLanguage: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggleSelectAll) {
            Text(
                if (selectedIndices.size == lineCount && lineCount > 0) "Deselect all" else "Select all",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TextButton(onClick = onPickLanguage) {
            Icon(Icons.Filled.GTranslate, contentDescription = "Translation language", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(targetLanguage?.uppercase() ?: "Translate", style = MaterialTheme.typography.labelMedium)
        }
        if (selectedIndices.isNotEmpty()) {
            Text(
                "${selectedIndices.size} / $lineCount selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (selectedIndices.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            val delta = batchDeltaText.toLongOrNull() ?: 0L
            OutlinedTextField(
                value = batchDeltaText,
                onValueChange = onBatchDeltaChange,
                modifier = Modifier.width(76.dp),
                singleLine = true,
                label = { Text("ms") },
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = { onBatchApply(-delta) },
                enabled = delta > 0,
                modifier = Modifier.height(40.dp),
            ) { Text("−") }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { onBatchApply(+delta) },
                enabled = delta > 0,
                modifier = Modifier.height(40.dp),
            ) { Text("+") }
        }
    }
}

// ── Fine-tune line item ────────────────────────────────────────────────────────

@Composable
private fun FinetuneLineItem(
    line: EditorLine,
    isSelected: Boolean,
    selectionMode: Boolean,
    isSegmentPlaying: Boolean,
    targetLanguage: String?,
    onToggleSelect: () -> Unit,
    onPlaySegment: () -> Unit,
    onStopSegment: () -> Unit,
    onAdjust: (Long) -> Unit,
    onSetTimestamp: (Long) -> Unit,
    onEditText: (String) -> Unit,
    onRemove: () -> Unit,
    onPositioned: (windowTop: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isTranslating by remember { mutableStateOf(false) }
    var previewText by remember(line.text, targetLanguage) { mutableStateOf<String?>(null) }
    // Text this line had right before a preview (translation or romanization) was applied,
    // so it can be undone. Cleared once undone, edited manually, or a new preview is applied.
    var preEditText by remember { mutableStateOf<String?>(null) }

    fun translate() {
        val target = targetLanguage ?: return
        if (line.text.isBlank() || isTranslating) return
        isTranslating = true
        scope.launch {
            previewText = try {
                translateLyrics(listOf(LyricLine(0L, line.text)), target).firstOrNull()?.text
            } catch (e: Exception) {
                null
            }
            isTranslating = false
        }
    }

    fun romanize() {
        previewText = transliterateToLatin(line.text)
    }

    fun applyPreview(replacement: String) {
        preEditText = line.text
        onEditText(replacement)
        previewText = null
    }

    fun undoEdit() {
        val original = preEditText ?: return
        onEditText(original)
        preEditText = null
    }

    val timeMs = line.timeMs ?: 0L
    val minVal = (timeMs / 60_000).toInt()
    val secVal = ((timeMs % 60_000) / 1_000).toInt()
    val csVal = ((timeMs % 1_000) / 10).toInt()

    // Track the last ms value we pushed to the ViewModel so LaunchedEffect can
    // tell the difference between an "external" change (± button) and our own edit.
    var lastSetMs by remember { mutableStateOf(timeMs) }
    var minText by remember { mutableStateOf(minVal.toString().padStart(2, '0')) }
    var secText by remember { mutableStateOf(secVal.toString().padStart(2, '0')) }
    var csText by remember { mutableStateOf(csVal.toString().padStart(2, '0')) }

    LaunchedEffect(timeMs) {
        if (timeMs != lastSetMs) {
            minText = (timeMs / 60_000).toString().padStart(2, '0')
            secText = ((timeMs % 60_000) / 1_000).toString().padStart(2, '0')
            csText = ((timeMs % 1_000) / 10).toString().padStart(2, '0')
            lastSetMs = timeMs
        }
    }

    fun commit(mStr: String, sStr: String, cStr: String) {
        val m = mStr.toIntOrNull() ?: return
        val s = sStr.toIntOrNull()?.coerceIn(0, 59) ?: return
        val c = cStr.toIntOrNull()?.coerceIn(0, 99) ?: return
        val ms = m * 60_000L + s * 1_000L + c * 10L
        lastSetMs = ms
        onSetTimestamp(ms)
    }

    val bgColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surface,
        label = "sel_bg",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> onPositioned(coords.positionInWindow().y) }
            .background(bgColor)
            .clickable(enabled = selectionMode, onClick = onToggleSelect),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = line.text,
                onValueChange = {
                    // A manual edit invalidates any pending "undo" for this line.
                    preEditText = null
                    onEditText(it)
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text("Lyric text", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                },
            )
            IconButton(onClick = ::translate, enabled = targetLanguage != null && !isTranslating) {
                if (isTranslating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.GTranslate,
                        contentDescription = "Translate line",
                        tint = if (targetLanguage != null) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
            if (isTransliterationSupported) {
                IconButton(onClick = ::romanize) {
                    Icon(
                        Icons.Filled.Abc,
                        contentDescription = "Show in Latin script",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }

        val currentPreview = previewText
        val revertText = preEditText
        if (currentPreview != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                    .clickable { applyPreview(currentPreview) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Apply",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = currentPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (revertText != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                    .clickable { undoEdit() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Undo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Timestamp editor + ±100ms buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = if (isSegmentPlaying) onStopSegment else onPlaySegment,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (isSegmentPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isSegmentPlaying) "Stop segment" else "Play segment",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(6.dp))
            TsField(value = minText, label = "mm", onValueChange = {
                minText = it.filter(Char::isDigit).take(2)
                commit(minText, secText, csText)
            })
            Text(":", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 2.dp))
            TsField(value = secText, label = "ss", onValueChange = {
                secText = it.filter(Char::isDigit).take(2)
                commit(minText, secText, csText)
            })
            Text(".", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 2.dp))
            TsField(value = csText, label = "cs", onValueChange = {
                csText = it.filter(Char::isDigit).take(2)
                commit(minText, secText, csText)
            })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onAdjust(-100L) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "−100ms", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onAdjust(+100L) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "+100ms", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Compact timestamp field (mm / ss / cs) ────────────────────────────────────

@Composable
private fun TsField(value: String, label: String, onValueChange: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Tappable insert-line divider between items ────────────────────────────────

@Composable
private fun InsertLineDivider(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Icon(
            Icons.Filled.Add,
            contentDescription = "Insert line here",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
