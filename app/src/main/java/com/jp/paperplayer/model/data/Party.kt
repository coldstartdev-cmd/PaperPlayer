package com.jp.paperplayer.model.data

import kotlin.math.abs

/** A party advertised on the LAN, discovered and resolved via mDNS. */
data class DiscoveredParty(
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
)

enum class PartyMemberStatus { JOINING, SYNCING, DOWNLOADING, READY, PLAYING, LOST }

/**
 * At-a-glance sync health, derived from a member's drift history rather than
 * just its current sample: a single noisy tick shouldn't flip a device's
 * status, and a device that's steadily drifting worse should be flagged even
 * while its instantaneous drift still looks small.
 */
enum class SyncHealth { UNKNOWN, IN_SYNC, DRIFTING, FALLING_OUT_OF_SYNC }

/**
 * Live sync telemetry a guest reports to the host for the sync dashboard —
 * the same raw numbers driving the guest's own on-device debug panel, not a
 * pre-collapsed drift figure, so the host can see exactly what the guest
 * observed and reconstruct timing itself. [driftMs] and [clockOffsetMs] are
 * derived rather than stored so there's a single source of truth.
 */
data class PartyMemberSyncStats(
    /** This guest's own elapsedRealtime when the sample was taken. */
    val deviceAtMs: Long,
    /** [deviceAtMs] converted to the host's clock via the guest's measured offset. */
    val hostAtMs: Long,
    /** Where the party timeline said playback should be at [deviceAtMs]. */
    val expectedPositionMs: Long,
    /** Where the guest's player actually was at [deviceAtMs]. */
    val actualPositionMs: Long,
    val rttMs: Long,
    val playbackSpeed: Float,
    val seekCorrections: Int,
    val nudgeCorrections: Int,
    /** The guest's current audio latency trim, for trim suggestions. */
    val latencyTrimMs: Long = 0L,
    /** Recent drift samples accumulated host-side, oldest first (bounded). */
    val driftHistory: List<Long> = emptyList(),
    /**
     * Gap between this sample's [hostAtMs] and the previous one's for this
     * guest — around 500ms (the drift-tick interval) when healthy. A much
     * larger gap means a delayed or dropped SYNC_STATS message: a WiFi
     * hiccup on the control channel, not genuine audio drift.
     */
    val lastSampleGapMs: Long = 0L,
    /** Running count of gaps large enough to suspect a control-channel hiccup rather than normal jitter. */
    val networkGapCount: Int = 0,
    /**
     * Where the host's own authoritative timeline (plus this guest's own
     * [latencyTrimMs]) says this guest should be at [hostAtMs] — recomputed
     * independently rather than trusting the guest's self-reported
     * [expectedPositionMs], which reflects that guest's own (possibly
     * stale) view if it missed recent HOST_SYNC updates during a network
     * hiccup. Trim is included deliberately: a correctly-trimmed guest is
     * *supposed* to sit that far from the host's raw position once its
     * drift loop locks on, so leaving trim out would flag every
     * well-calibrated nonzero trim as "drifting". Null when the host has no
     * live timeline to check against (mid-transition, or the sample
     * predates the current one).
     */
    val hostVerifiedExpectedPositionMs: Long? = null,
    /** Times the host has forced this guest back onto the timeline after [syncHealth] read FALLING_OUT_OF_SYNC. */
    val hostResyncCount: Int = 0,
) {
    /** actual - expected position at the last sample, using the guest's own self-reported expected; positive = ahead of the party. */
    val driftMs: Long get() = actualPositionMs - expectedPositionMs

    /**
     * The more trustworthy figure: actual position against the host's own
     * recomputed expected position instead of the guest's self-report. Falls
     * back to [driftMs] when a host-side reference isn't available. This is
     * what [syncHealth] and the host's auto-resync watchdog act on.
     */
    val verifiedDriftMs: Long
        get() = hostVerifiedExpectedPositionMs?.let { actualPositionMs - it } ?: driftMs

    /** hostClock - guestClock, from the last sample. */
    val clockOffsetMs: Long get() = hostAtMs - deviceAtMs

    /**
     * Persistent residual bias: mean drift over the accumulated (recalculated,
     * trim-inclusive) history. Should sit near zero once a correctly-trimmed
     * guest's drift loop has locked on — a nonzero mean here is the position
     * loop's own leftover bias on top of whatever trim is already applied,
     * not the trim value itself.
     */
    val meanDriftMs: Long
        get() = if (driftHistory.isEmpty()) 0L else driftHistory.sum() / driftHistory.size

    /**
     * Trim that would re-center this device on the timeline the current trim
     * was validated against; meaningful once enough history has accumulated.
     */
    val suggestedTrimMs: Long get() = latencyTrimMs - meanDriftMs

    /**
     * Short rolling average of the last few (recalculated) drift samples,
     * rather than just the latest one. The correction loop only nudges
     * every second or two, so any single sample can catch a device
     * mid-swing between corrections; judging IN_SYNC/DRIFTING off that one
     * tick flipped the label back and forth every ~500ms even though the
     * device was behaving consistently. Averaging over ~2s smooths that out
     * without hiding a real, sustained problem.
     */
    val recentDriftMs: Long
        get() {
            val window = driftHistory.takeLast(SyncHealthThresholds.RECENT_WINDOW)
                .ifEmpty { listOf(verifiedDriftMs) }
            return window.sum() / window.size
        }

    /**
     * Compares the mean |drift| of the older vs. newer half of the retained
     * (recalculated) history: a device whose drift-correction loop is
     * keeping up oscillates around a roughly stable magnitude, while one
     * that's losing the fight (network degrading, nudge pegged at its limit)
     * trends upward across the window even if any single sample still looks
     * fine.
     */
    val syncHealth: SyncHealth
        get() {
            if (driftHistory.size < SyncHealthThresholds.MIN_TREND_SAMPLES) {
                return if (abs(recentDriftMs) <= SyncHealthThresholds.IN_SYNC_MS) SyncHealth.IN_SYNC else SyncHealth.DRIFTING
            }
            val mid = driftHistory.size / 2
            val olderMeanAbs = driftHistory.take(mid).map { abs(it) }.average()
            val recentMeanAbs = driftHistory.drop(mid).map { abs(it) }.average()
            val worsening = recentMeanAbs - olderMeanAbs
            return when {
                worsening > SyncHealthThresholds.TREND_MS && recentMeanAbs > SyncHealthThresholds.FALLING_FLOOR_MS ->
                    SyncHealth.FALLING_OUT_OF_SYNC
                abs(recentDriftMs) <= SyncHealthThresholds.IN_SYNC_MS -> SyncHealth.IN_SYNC
                else -> SyncHealth.DRIFTING
            }
        }
}

private object SyncHealthThresholds {
    /** Below this, drift is inaudible per user listening tests — "in sync". */
    const val IN_SYNC_MS = 15L

    /**
     * History needed before trusting a trend over a single sample (8s at
     * the 500ms drift-tick rate). Bumped from 5s: the guest's own
     * corrective loop now only adjusts once per ~1.5s (see
     * ADJUSTMENT_COOLDOWN_MS), so a shorter window could judge it "still
     * failing" mid-way through its own correction cadence.
     */
    const val MIN_TREND_SAMPLES = 16

    /** Samples averaged for [PartyMemberSyncStats.recentDriftMs] (~2s at the 500ms sample rate). */
    const val RECENT_WINDOW = 4

    /**
     * How much the recent half must exceed the older half to call it
     * "worsening" rather than noise. Kept comfortably above ordinary WiFi
     * jitter in the host-verification anchor (rttMs/2 estimate), which by
     * itself can wobble by a few tens of ms between samples.
     */
    const val TREND_MS = 30.0

    /**
     * Floor on the recent magnitude itself. This is deliberately close to
     * the guest's own DRIFT_SEEK_THRESHOLD_MS (150ms): the guest's nudge
     * loop already quietly absorbs anything below that on its own, so
     * FALLING_OUT_OF_SYNC — which triggers a hard, audible pause-and-resync
     * on the host side — should only fire when the guest's local loop is
     * genuinely losing ground, not for drift levels it's already designed
     * to handle by itself. Set too low, host verification (which is more
     * accurate than the old guest-self-report check) ends up triggering
     * far more resyncs than the old logic did, even though nothing is
     * actually audibly wrong — the fix for one problem (false "in sync")
     * created a new one (over-eager resyncs) until this floor was raised.
     */
    const val FALLING_FLOOR_MS = 100.0
}

/**
 * Frequency emphasis a device is playing with — a "distributed speaker"
 * party trick for a group with several devices: assign one to lean into
 * bass, another mid, another treble. Host-assigned, applied locally via a
 * platform Equalizer (see [PartyEqController][com.jp.paperplayer.party.PartyEqController]).
 */
enum class PartyEqRole { NONE, BASS, MID, TREBLE }

/** A guest connected to the party, as seen by the host. */
data class PartyMember(
    val id: String,
    val name: String,
    val status: PartyMemberStatus,
    val stats: PartyMemberSyncStats? = null,
    val eqRole: PartyEqRole = PartyEqRole.NONE,
)
