package com.jp.paperplayer.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jp.paperplayer.ui.theme.PaperPlayerTheme
import kotlin.math.abs

// Lyrics are meant to be read — always shown at the largest comfortable size.
private const val LYRICS_FONT_SIZE_SP = 28

// ── Shared lyric line: uniform font size, opacity fades with distance ─────────

@Composable
fun LyricLineItem(
    text: String,
    index: Int,
    currentIndex: Int,
    timeMs: Long? = null,
    currentTimeMs: Long? = null,
    overrideColors: Boolean = false,
) {
    // currentIndex < 0 means there's no timing info to highlight against (e.g. plain,
    // untimed lyrics) — show every line at full strength instead of dimming everything.
    val hasActiveLine = currentIndex >= 0
    val distance = if (hasActiveLine) abs(index - currentIndex) else Int.MAX_VALUE
    // Two lines can share the exact same timestamp (e.g. right after duplicating a line in the
    // sync editor, before it's re-timed) — both should read as "current" together, not just
    // whichever one happens to be findCurrentLyricIndex()'s pick.
    val sharesCurrentTimestamp = timeMs != null && currentTimeMs != null && timeMs == currentTimeMs
    val isActive = hasActiveLine && (distance == 0 || sharesCurrentTimestamp)

    val itemAlpha by animateFloatAsState(
        targetValue = when {
            !hasActiveLine -> 1.00f
            isActive -> 1.00f
            distance == 1 -> 0.70f
            distance == 2 -> 0.45f
            else -> 0.25f
        },
        label = "alpha_$index",
    )

    val activeColor = if (overrideColors) Color.White else MaterialTheme.colorScheme.primary
    val inactiveColor = if (overrideColors) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val normalColor = if (overrideColors) Color.White else MaterialTheme.colorScheme.onSurface

    val color by animateColorAsState(
        targetValue = when {
            !hasActiveLine -> normalColor
            isActive -> activeColor
            else -> inactiveColor
        },
        label = "color_$index",
    )

    Text(
        text = text,
        fontSize = LYRICS_FONT_SIZE_SP.sp,
        lineHeight = (LYRICS_FONT_SIZE_SP * 1.4f).sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(itemAlpha),
    )
}

@Preview(showBackground = true)
@Composable
private fun LyricLineItemPreview() {
    PaperPlayerTheme {
        Column {
            LyricLineItem(text = "Two lines away", index = 0, currentIndex = 2)
            LyricLineItem(text = "One line away", index = 1, currentIndex = 2)
            LyricLineItem(text = "The current line", index = 2, currentIndex = 2)
            LyricLineItem(text = "One line ahead", index = 3, currentIndex = 2)
        }
    }
}

@Preview(showBackground = true, name = "Untimed (plain lyrics)")
@Composable
private fun LyricLineItemUntimedPreview() {
    PaperPlayerTheme {
        Column {
            LyricLineItem(text = "Plain lyric line one", index = 0, currentIndex = -1)
            LyricLineItem(text = "Plain lyric line two", index = 1, currentIndex = -1)
            LyricLineItem(text = "Plain lyric line three", index = 2, currentIndex = -1)
        }
    }
}

@Preview(showBackground = true, name = "Duplicate timestamp — both current")
@Composable
private fun LyricLineItemTiedTimestampPreview() {
    PaperPlayerTheme {
        Column {
            LyricLineItem(text = "Before", index = 0, currentIndex = 1, timeMs = 0L, currentTimeMs = 4_000L)
            LyricLineItem(text = "Current — first copy", index = 1, currentIndex = 1, timeMs = 4_000L, currentTimeMs = 4_000L)
            LyricLineItem(text = "Current — duplicated copy", index = 2, currentIndex = 1, timeMs = 4_000L, currentTimeMs = 4_000L)
            LyricLineItem(text = "After", index = 3, currentIndex = 1, timeMs = 8_000L, currentTimeMs = 4_000L)
        }
    }
}
