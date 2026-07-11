package com.jp.paperplayer.ui.party

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.data.SyncHealth
import kotlin.math.abs

/**
 * Small composables and formatting helpers shared by [PartyHostView] and
 * [PartyGuestView] (drift charts, debug rows, the EQ role picker, sync
 * health/countdown chips) — kept together since none of them is large or
 * complex enough to warrant its own file.
 */
@Composable
internal fun DriftSparkline(history: List<Long>) {
    if (history.size < 2) return
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    Text(
        text = "Drift over the last ${history.size / 2} seconds",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(vertical = 4.dp),
    ) {
        val range = maxOf(history.maxOf { abs(it) }, 50L).toFloat()
        val midY = size.height / 2f
        drawLine(axisColor, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
        val stepX = size.width / (history.size - 1).coerceAtLeast(1)
        var previous: Offset? = null
        history.forEachIndexed { index, drift ->
            val point = Offset(
                x = index * stepX,
                y = midY - (drift / range) * (size.height / 2f - 2f),
            )
            previous?.let { drawLine(lineColor, it, point, strokeWidth = 3f) }
            previous = point
        }
    }
}

/** Off/Bass/Mid/Treble picker for the party-trick distributed-speaker EQ. */
@Composable
internal fun EqRoleRow(current: PartyEqRole, onSelect: (PartyEqRole) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EQ_ROLE_OPTIONS.forEach { (role, label) ->
            val selected = role == current
            val padding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            if (selected) {
                Button(onClick = { onSelect(role) }, contentPadding = padding) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(onClick = { onSelect(role) }, contentPadding = padding) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private val EQ_ROLE_OPTIONS = listOf(
    PartyEqRole.NONE to "Off",
    PartyEqRole.BASS to "Bass",
    PartyEqRole.MID to "Mid",
    PartyEqRole.TREBLE to "Treble",
)

@Composable
internal fun DebugRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

internal fun formatDebugSeconds(ms: Long): String = "%d.%01ds".format(ms / 1000, (ms % 1000) / 100)

internal const val TRIM_STEP_MS = 5L
internal val TRIM_RANGE_MS = -300L..300L

/** Only surface a trim suggestion once the bias is established and audible-adjacent. */
internal const val TRIM_SUGGESTION_MIN_SAMPLES = 20
internal const val TRIM_SUGGESTION_THRESHOLD_MS = 3L

/**
 * Gap between the guest's self-reported drift and the host's recomputed one
 * worth calling out — a sign the guest's own view is stale. Widened past the
 * two figures' theoretical noise floor (host-side verification is anchored
 * on receipt time minus half the measured RTT, an approximation with its own
 * few-ms error) so normal jitter doesn't read as staleness.
 */
internal const val HOST_VERIFIED_MISMATCH_MS = 35L

internal fun formatTrim(trimMs: Long): String = "${if (trimMs > 0) "+" else ""}$trimMs ms"

@Composable
internal fun StartCountdown(remainingMs: Long) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = "Starting in ${(remainingMs + 999) / 1000}s",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Small colored dot next to a member's status, for a quick glance without opening the sync dashboard. */
@Composable
internal fun SyncHealthDot(health: SyncHealth) {
    val color = when (health) {
        SyncHealth.IN_SYNC -> MaterialTheme.colorScheme.primary
        SyncHealth.DRIFTING -> MaterialTheme.colorScheme.tertiary
        SyncHealth.FALLING_OUT_OF_SYNC -> MaterialTheme.colorScheme.error
        SyncHealth.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
}

internal fun syncHealthLabel(health: SyncHealth): String = when (health) {
    SyncHealth.IN_SYNC -> "In sync"
    SyncHealth.DRIFTING -> "Drifting"
    SyncHealth.FALLING_OUT_OF_SYNC -> "Falling out of sync"
    SyncHealth.UNKNOWN -> "Measuring…"
}
