package com.jp.paperplayer.ui.lyricseditor

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jp.paperplayer.model.ui.EditorLine
import com.jp.paperplayer.model.ui.LyricsEditorState
import com.jp.paperplayer.model.ui.PlayerState
import com.jp.paperplayer.ui.components.formatDuration
import com.jp.paperplayer.ui.preview.PreviewFixtures
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import kotlin.math.abs
import kotlinx.coroutines.launch

// ── Phase 2: play song and tap to stamp each line ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncingPhase(
    editorState: LyricsEditorState,
    playerState: PlayerState,
    filePath: String?,
    songUri: Uri?,
    snackbarHostState: SnackbarHostState,
    onStamp: () -> Unit,
    onUndo: () -> Unit,
    onToggleMode: () -> Unit,
    onBackToEditing: () -> Unit,
    onSave: (filePath: String, onResult: (success: Boolean, permissionDenied: Boolean) -> Unit) -> Unit,
    onSaveSuccess: () -> Unit,
    onPermissionNeeded: (Uri, retry: () -> Unit) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPause: () -> Unit,
    onAdjust: (index: Int, deltaMs: Long) -> Unit,
    onSetTimestamp: (index: Int, timeMs: Long) -> Unit,
    canUndoAdjust: Boolean,
    onUndoAdjust: () -> Unit,
    onBeginReorder: () -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onDuplicate: (index: Int) -> Unit,
    onRemoveLine: (index: Int) -> Unit,
    onEditText: (index: Int, text: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lyricsListState = rememberLazyListState()
    val currentIndex = editorState.currentIndex
    val lines = editorState.lines
    val allStamped = currentIndex >= lines.size

    // Free selection of any line is only meaningful once every line has a timestamp —
    // during the initial tap-along pass, rows stay locked to the sequential currentIndex.
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var segmentEndMs by remember { mutableStateOf<Long?>(null) }
    var playingIndex by remember { mutableStateOf<Int?>(null) }
    var isEditingText by remember { mutableStateOf(false) }

    // Drag-to-reorder bookkeeping (review mode only). windowTop per row comes from each item's
    // own onGloballyPositioned, mirroring the drag-select hit-testing already used in
    // FinetuningPhase — the gesture lives on the Box wrapping the list, not on individual rows,
    // so it keeps tracking correctly across swaps instead of being tied to a shifting row index.
    // The dragged row is rendered at the finger's absolute position each frame (recomputed from
    // dragWindowY every time, not accumulated), so it can't drift out of sync after several swaps.
    val itemWindowTops = remember { mutableStateMapOf<Int, Float>() }
    var boxWindowTop by remember { mutableStateOf(0f) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragWindowY by remember { mutableStateOf<Float?>(null) }
    var dragStartOffsetInRow by remember { mutableStateOf(0f) }

    fun hitTest(windowY: Float): Int =
        itemWindowTops.entries.filter { it.value <= windowY }.maxByOrNull { it.value }?.key ?: -1

    LaunchedEffect(lines.size) {
        itemWindowTops.clear()
    }

    LaunchedEffect(allStamped) {
        if (!allStamped) {
            selectedIndex = null
            segmentEndMs = null
            playingIndex = null
            isEditingText = false
        }
        draggedIndex = null
        dragWindowY = null
    }

    // Only meaningful during the tap-along pass, to keep the line currently being stamped in
    // view. Once allStamped, currentIndex is just the "done" sentinel (== lines.size) — scrolling
    // off that would jump to the bottom of the list every time review mode is entered.
    LaunchedEffect(currentIndex) {
        if (allStamped) return@LaunchedEffect
        val target = currentIndex.coerceAtMost(lines.size - 1)
        if (target >= 0) lyricsListState.animateScrollToItem((target - 3).coerceAtLeast(0))
    }

    LaunchedEffect(playerState.positionMs) {
        val end = segmentEndMs
        if (end != null && playerState.positionMs >= end) {
            onPause()
            segmentEndMs = null
            playingIndex = null
        }
    }

    LaunchedEffect(playerState.isPlaying) {
        if (!playerState.isPlaying) {
            segmentEndMs = null
            playingIndex = null
        }
    }

    fun playSegment(index: Int) {
        val line = lines[index]
        onSeek(line.timeMs ?: 0L)
        if (!playerState.isPlaying) onTogglePlayPause()
        segmentEndMs = lines.getOrNull(index + 1)?.timeMs
        playingIndex = index
    }

    fun stopSegment() {
        onPause()
        segmentEndMs = null
        playingIndex = null
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Lyrics") },
                navigationIcon = {
                    IconButton(onClick = onBackToEditing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to editing")
                    }
                },
                actions = {
                    val stamped = lines.count { it.timeMs != null }
                    if (lines.any { it.timeMs != null }) {
                        IconButton(onClick = onToggleMode) {
                            Icon(
                                Icons.Filled.SwapHoriz,
                                contentDescription = if (allStamped) "Switch to stamp mode" else "Switch to review mode",
                            )
                        }
                    }
                    if (allStamped) {
                        IconButton(onClick = onUndoAdjust, enabled = canUndoAdjust) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                    }
                    when {
                        editorState.isSaving -> CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                        stamped > 0 -> {
                            IconButton(onClick = ::doSave) {
                                Icon(Icons.Filled.Check, contentDescription = "Save lyrics")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Only shown during the tap-along pass, to control playback while stamping — in
            // review mode timing/play controls live on each row instead, so there's nothing
            // for this header to show.
            if (!allStamped) {
                PlaybackOnlyHeader(
                    playerState = playerState,
                    onTogglePlayPause = onTogglePlayPause,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                HorizontalDivider()
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords -> boxWindowTop = coords.positionInWindow().y }
                    .then(
                        if (allStamped) {
                            Modifier.pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val windowY = boxWindowTop + offset.y
                                        val hit = hitTest(windowY)
                                        if (hit >= 0) {
                                            onBeginReorder()
                                            draggedIndex = hit
                                            dragStartOffsetInRow = windowY - (itemWindowTops[hit] ?: windowY)
                                            dragWindowY = windowY
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        val current = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                        val windowY = boxWindowTop + change.position.y
                                        dragWindowY = windowY
                                        val hit = hitTest(windowY)
                                        if (hit >= 0 && hit != current) {
                                            onReorder(current, hit)
                                            draggedIndex = hit
                                        }
                                    },
                                    onDragEnd = { draggedIndex = null; dragWindowY = null },
                                    onDragCancel = { draggedIndex = null; dragWindowY = null },
                                )
                            }
                        } else Modifier
                    ),
            ) {
                LazyColumn(state = lyricsListState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { index, line ->
                        // Rendered from the finger's absolute position each frame, not an
                        // accumulated delta, so it can't drift after several swaps mid-drag.
                        val dragOffsetPx = if (index == draggedIndex) {
                            val restTop = itemWindowTops[index] ?: 0f
                            (dragWindowY ?: restTop) - dragStartOffsetInRow - restTop
                        } else 0f
                        val isSelected = index == selectedIndex
                        SyncLineItem(
                            line = line,
                            index = index,
                            currentIndex = currentIndex,
                            endMs = lines.getOrNull(index + 1)?.timeMs,
                            isSelected = isSelected,
                            selectable = allStamped,
                            isDragging = index == draggedIndex,
                            dragOffsetPx = dragOffsetPx,
                            isSegmentPlaying = isSelected && playingIndex == index && playerState.isPlaying,
                            isEditingText = isSelected && isEditingText,
                            onSelect = {
                                selectedIndex = if (selectedIndex == index) null else index
                                isEditingText = false
                            },
                            onPlaySegment = { playSegment(index) },
                            onStopSegment = ::stopSegment,
                            onEditText = { text -> onEditText(index, text) },
                            onDoneEditing = { isEditingText = false },
                            onPositioned = { windowTop -> itemWindowTops[index] = windowTop },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!allStamped) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onUndo, enabled = currentIndex > 0) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Undo")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onStamp, modifier = Modifier.height(52.dp)) {
                            Text(text = "TAP TO STAMP", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // currentIndex, not a raw timeMs != null count — during a toggled-into
                        // redo pass, later lines still carry timestamps from the previous pass
                        // until they're re-tapped, so a raw count would overstate progress.
                        "$currentIndex / ${lines.size} lines stamped",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val sel = selectedIndex
                    val selLine = sel?.let { lines.getOrNull(it) }
                    val selMs = selLine?.timeMs ?: 0L
                    val minVal = (selMs / 60_000).toInt()
                    val secVal = ((selMs % 60_000) / 1_000).toInt()
                    val csVal = ((selMs % 1_000) / 10).toInt()
                    fun commit(m: Int, s: Int, c: Int) {
                        sel?.let { onSetTimestamp(it, m * 60_000L + s * 1_000L + c * 10L) }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ScrubbableTimeUnit(
                            value = minVal, label = "mm", wrapAt = 100, enabled = sel != null,
                            onChange = { commit(it, secVal, csVal) },
                        )
                        Text(":", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 2.dp))
                        ScrubbableTimeUnit(
                            value = secVal, label = "ss", wrapAt = 60, enabled = sel != null,
                            onChange = { commit(minVal, it, csVal) },
                        )
                        Text(".", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 2.dp))
                        ScrubbableTimeUnit(
                            value = csVal, label = "cs", wrapAt = 100, enabled = sel != null,
                            onChange = { commit(minVal, secVal, it) },
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { if (sel != null) isEditingText = !isEditingText },
                            enabled = sel != null,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = if (isEditingText) "Stop editing line text" else "Edit line text",
                                tint = if (isEditingText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(
                            onClick = { sel?.let { onDuplicate(it) } },
                            enabled = sel != null,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate line", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                sel?.let {
                                    onRemoveLine(it)
                                    selectedIndex = null
                                }
                            },
                            enabled = sel != null,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete line",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { sel?.let { onAdjust(it, -100L) } },
                            enabled = sel != null,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "−100ms", modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(
                            onClick = { sel?.let { onAdjust(it, +100L) } },
                            enabled = sel != null,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "+100ms", modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (sel != null) "Adjusting line ${sel + 1} of ${lines.size}"
                               else "All lines stamped — tap a line to fine-tune",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncingPhasePreview() {
    PaperPlayerTheme {
        SyncingPhase(
            editorState = LyricsEditorState(lines = PreviewFixtures.editorLines, currentIndex = 2),
            playerState = PreviewFixtures.playerState,
            filePath = PreviewFixtures.song1.filePath,
            songUri = PreviewFixtures.song1.uri,
            snackbarHostState = remember { SnackbarHostState() },
            onStamp = {},
            onUndo = {},
            onToggleMode = {},
            onBackToEditing = {},
            onSave = { _, _ -> },
            onSaveSuccess = {},
            onPermissionNeeded = { _, _ -> },
            onTogglePlayPause = {},
            onSeek = {},
            onPause = {},
            onAdjust = { _, _ -> },
            onSetTimestamp = { _, _ -> },
            canUndoAdjust = false,
            onUndoAdjust = {},
            onBeginReorder = {},
            onReorder = { _, _ -> },
            onDuplicate = {},
            onRemoveLine = {},
            onEditText = { _, _ -> },
        )
    }
}

// ── Header shown while a fresh tap-along pass is still in progress ────────────

@Composable
private fun PlaybackOnlyHeader(
    playerState: PlayerState,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${formatDuration(playerState.positionMs)} / ${formatDuration(playerState.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── A single mm/ss/cs digit pair — drag up/down to scroll through its values ──

@Composable
private fun ScrubbableTimeUnit(
    value: Int,
    label: String,
    wrapAt: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    var dragAccumPx by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    // Smaller than a real wheel's row height — most of the felt "sensitivity" of a scrub
    // control comes from how little travel it takes to register a step.
    val stepPx = with(density) { 8.dp.toPx() }

    // Modifier.pointerInput(Unit) launches its gesture-detection coroutine once and keeps it
    // running across recompositions — without this, the block below would keep closing over
    // the value/onChange from the moment the line was first selected, so every drag would just
    // recompute the same "+1" from that stale value instead of advancing (looked like the
    // widget wasn't scrolling at all after the first tick).
    val currentValue by rememberUpdatedState(value)
    val currentWrapAt by rememberUpdatedState(wrapAt)
    val currentOnChange by rememberUpdatedState(onChange)

    // While actively dragging, the digit tracks the finger 1:1 (snap — no lag). Once released
    // (or after a step is consumed and the accumulator resets), it springs back to center,
    // giving the "notch" a physical settle instead of a flat jump.
    val offset by animateFloatAsState(
        targetValue = dragAccumPx,
        animationSpec = if (isDragging) snap() else spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "scrub_offset",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(44.dp)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragAccumPx = 0f
                            },
                            onDragEnd = {
                                isDragging = false
                                dragAccumPx = 0f
                            },
                            onDragCancel = {
                                isDragging = false
                                dragAccumPx = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                // Dragging up decreases dragAmount (negative), which should
                                // scroll the value up — like scrolling a wheel's content.
                                dragAccumPx -= dragAmount
                                // Count net steps first, then apply once against the freshest
                                // value — calling onChange repeatedly inside the loop would keep
                                // reusing the same pre-drag value for every step in a fast flick.
                                var steps = 0
                                while (dragAccumPx >= stepPx) {
                                    steps++
                                    dragAccumPx -= stepPx
                                }
                                while (dragAccumPx <= -stepPx) {
                                    steps--
                                    dragAccumPx += stepPx
                                }
                                if (steps != 0) {
                                    currentOnChange((currentValue + steps).mod(currentWrapAt))
                                    repeat(abs(steps)) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                }
                            },
                        )
                    }
                } else Modifier
            )
            .border(
                width = 1.dp,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    isDragging -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(6.dp),
            )
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = value.toString().padStart(2, '0'),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.graphicsLayer { translationY = -offset },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Single line item in the syncing list ─────────────────────────────────────

@Composable
private fun SyncLineItem(
    line: EditorLine,
    index: Int,
    currentIndex: Int,
    endMs: Long?,
    isSelected: Boolean,
    selectable: Boolean,
    isDragging: Boolean,
    dragOffsetPx: Float,
    isSegmentPlaying: Boolean,
    isEditingText: Boolean,
    onSelect: () -> Unit,
    onPlaySegment: () -> Unit,
    onStopSegment: () -> Unit,
    onEditText: (String) -> Unit,
    onDoneEditing: () -> Unit,
    onPositioned: (windowTop: Float) -> Unit,
) {
    val isCurrent = index == currentIndex
    val isPast = index < currentIndex

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> onPositioned(coords.positionInWindow().y) }
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffsetPx }
            .clickable(enabled = selectable, onClick = onSelect)
            .background(
                when {
                    isDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    isCurrent -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .alpha(if (isCurrent || isPast) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            // Selecting a line turns this into a play/stop button for its segment, instead of
            // needing a separate play control elsewhere — unselected rows just show the plain
            // drag handle, which the row's own long-press-drag (on the parent Box) responds to.
            if (isSelected) {
                IconButton(
                    onClick = if (isSegmentPlaying) onStopSegment else onPlaySegment,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (isSegmentPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (isSegmentPlaying) "Stop segment" else "Play segment",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            if (isEditingText) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                BasicTextField(
                    value = line.text,
                    onValueChange = onEditText,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDoneEditing() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            } else {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            val startText = line.timeMs?.let(::formatTimestamp) ?: if (isCurrent) "→" else "——:——.——"
            val timingText = if (selectable) "$startText – ${endMs?.let(::formatTimestamp) ?: "end"}" else startText
            Text(
                text = timingText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected || isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected || isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTimestamp(ms: Long): String =
    "%02d:%02d.%02d".format(ms / 60_000, (ms % 60_000) / 1_000, (ms % 1_000) / 10)
