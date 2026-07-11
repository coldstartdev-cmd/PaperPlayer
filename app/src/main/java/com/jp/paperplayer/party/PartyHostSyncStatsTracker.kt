package com.jp.paperplayer.party

import android.util.Log
import com.jp.paperplayer.model.data.PartyMemberSyncStats

/**
 * Read-only view of the host's own playback timeline, needed to verify a
 * guest's self-reported drift against the host's actual position rather
 * than trusting the guest's own possibly-stale view. Implemented by
 * [PartyHostEngine] as a thin passthrough to its own `@Volatile` fields —
 * every property here must stay a live read, never a cached copy, since
 * these are written from the Main-dispatched drift loop and read from
 * independent IO-dispatched coroutines.
 */
interface HostTimelineSnapshot {
    val preparing: Boolean
    val hostDriftActive: Boolean
    val lastStartAtHostMs: Long
    val lastKnownHostPositionMs: Long
    val lastKnownHostSampleAtMs: Long
}

/**
 * Accumulates and recalculates per-guest sync telemetry (SYNC_STATS) for the
 * host's sync dashboard. The drift fed into history/health isn't the
 * guest's self-reported figure — it's recomputed against [timeline]
 * ([hostVerifiedExpectedPositionMs]), since the guest's own "expected"
 * position reflects whatever it last heard, which can be stale if it missed
 * recent HOST_SYNC updates during a network hiccup.
 */
class PartyHostSyncStatsTracker(private val timeline: HostTimelineSnapshot) {
    private val memberStats = mutableMapOf<String, PartyMemberSyncStats>()

    /**
     * Recalculates and stores one guest's sample; returns the updated stats,
     * or null if this sample was dropped (out-of-order/duplicate — see
     * below), in which case the caller should skip publishing/resync checks
     * entirely, matching the pre-extraction behavior.
     */
    fun record(memberId: String, deviceName: String, stats: PartyMessage.SyncStats, receivedAtHostMs: Long): PartyMemberSyncStats? {
        // Anchor the verification on the host's own receipt clock (minus
        // half the guest's measured RTT, approximating one-way transit)
        // rather than the guest's self-computed hostAtMs. That value bakes
        // in the guest's clock-offset ESTIMATE, which wobbles a few/tens of
        // ms between ping refreshes on its own — comparing against it isn't
        // actually independent verification, it's comparing the guest
        // against a slightly different snapshot of its own offset estimate,
        // which fires false positives on a perfectly healthy connection.
        val hostAnchorMs = receivedAtHostMs - stats.rttMs / 2
        val verifiedExpected = hostVerifiedExpectedPositionMs(hostAnchorMs, stats.latencyTrimMs)
        val recalculatedDrift = verifiedExpected?.let { stats.actualPositionMs - it }
            ?: (stats.actualPositionMs - stats.expectedPositionMs)
        val updated = synchronized(memberStats) {
            val previous = memberStats[memberId]
            // SYNC_STATS travels over UDP with each tick sent twice for loss
            // resilience — nothing stops a duplicate or a reordered earlier
            // tick from arriving after a newer one already landed. Comparing
            // against the guest's own deviceAtMs (monotonic on that guest)
            // catches both: a stale actualPositionMs would otherwise get
            // diffed against the host's *current* verified-expected position,
            // manufacturing a drift spike that was never real.
            if (previous != null && stats.deviceAtMs <= previous.deviceAtMs) {
                Log.d(
                    TAG,
                    "$deviceName: dropping out-of-order/duplicate SYNC_STATS " +
                        "(deviceAtMs=${stats.deviceAtMs} <= last accepted ${previous.deviceAtMs})",
                )
                return null
            }
            val history = ((previous?.driftHistory ?: emptyList()) + recalculatedDrift).takeLast(DRIFT_HISTORY_SIZE)
            val gapMs = previous?.let { stats.hostAtMs - it.hostAtMs } ?: 0L
            val networkGaps = (previous?.networkGapCount ?: 0) + if (gapMs > NETWORK_GAP_THRESHOLD_MS) 1 else 0
            val next = PartyMemberSyncStats(
                deviceAtMs = stats.deviceAtMs,
                hostAtMs = stats.hostAtMs,
                expectedPositionMs = stats.expectedPositionMs,
                actualPositionMs = stats.actualPositionMs,
                rttMs = stats.rttMs,
                playbackSpeed = stats.playbackSpeed,
                seekCorrections = stats.seekCorrections,
                nudgeCorrections = stats.nudgeCorrections,
                latencyTrimMs = stats.latencyTrimMs,
                driftHistory = history,
                lastSampleGapMs = gapMs,
                networkGapCount = networkGaps,
                hostVerifiedExpectedPositionMs = verifiedExpected,
                hostResyncCount = previous?.hostResyncCount ?: 0,
            )
            memberStats[memberId] = next
            next
        }
        Log.d(
            TAG,
            "$deviceName: guestDrift=${stats.actualPositionMs - stats.expectedPositionMs}ms " +
                "verifiedDrift=$recalculatedDrift ms (anchor=${if (verifiedExpected != null) "host" else "guest-fallback"}) " +
                "health=${updated.syncHealth} historySize=${updated.driftHistory.size} gapMs=${updated.lastSampleGapMs}",
        )
        return updated
    }

    /**
     * Where the host's own timeline says a guest should be at [hostAtMs] —
     * extrapolated from the host's own most recent real position sample,
     * independent of that guest's self-reported view. Not extrapolated from
     * [HostTimelineSnapshot.lastStartAtHostMs] (the last scheduled start,
     * which can be minutes ago): the host's system clock and its audio
     * hardware clock are different physical clocks, and even a small skew
     * between them would accumulate into a real gap over that long a
     * window, showing up as "drift" that was never actually audible.
     *
     * Includes [latencyTrimMs] deliberately: the guest's own drift loop
     * nudges/seeks its *raw position* to hit hostPosition + trim, so a
     * correctly-trimmed nonzero value is expected to show up as exactly
     * that much position offset once locked on — it's not an error. Leaving
     * trim out here meant a well-calibrated guest (say -70ms) always read as
     * "drifting" by roughly its own trim amount, while an *un*calibrated
     * guest (trim=0) that's actually audibly wrong read as "in sync" just
     * because raw position happened to match the host's with no offset.
     * This is a position-loop health check — "is the guest tracking its own
     * target" — not a check of whether that target's trim is acoustically
     * correct, which position data can never see (that's what the chirp
     * calibration is for).
     *
     * Null when there's no live epoch to check against: the host is
     * mid-transition, or [hostAtMs] predates the current epoch (a
     * stale/out-of-order report from just before the last track change,
     * resume, or seek).
     */
    private fun hostVerifiedExpectedPositionMs(hostAtMs: Long, latencyTrimMs: Long): Long? {
        if (timeline.preparing || !timeline.hostDriftActive) return null
        if (hostAtMs < timeline.lastStartAtHostMs) return null
        return timeline.lastKnownHostPositionMs + (hostAtMs - timeline.lastKnownHostSampleAtMs) + latencyTrimMs
    }

    /** Records that the host forced this guest back onto the timeline (auto-resync watchdog). */
    fun incrementResyncCount(memberId: String) {
        synchronized(memberStats) {
            memberStats[memberId]?.let { memberStats[memberId] = it.copy(hostResyncCount = it.hostResyncCount + 1) }
        }
    }

    fun snapshot(memberId: String): PartyMemberSyncStats? = synchronized(memberStats) { memberStats[memberId] }

    /**
     * Drops every guest's retained drift history. Called whenever a fresh
     * scheduled start begins — party-wide (track change, resume, seek) or a
     * single targeted auto-resync: the old samples span a position
     * discontinuity, and mixing them with post-start data corrupts both
     * [PartyMemberSyncStats.syncHealth]'s trend check and the trim-bias
     * suggestion.
     */
    fun clearAll() {
        synchronized(memberStats) {
            memberStats.keys.toList().forEach { id -> memberStats[id]?.let { memberStats[id] = it.copy(driftHistory = emptyList()) } }
        }
    }

    fun clear(memberId: String) {
        synchronized(memberStats) {
            memberStats[memberId]?.let { memberStats[memberId] = it.copy(driftHistory = emptyList()) }
        }
    }

    fun remove(memberId: String) {
        synchronized(memberStats) { memberStats.remove(memberId) }
    }

    private companion object {
        const val TAG = "PartyHostSyncStatsTracker"
        const val DRIFT_HISTORY_SIZE = 60

        /** Guest SYNC_STATS arrive every ~500ms on a healthy connection; a gap past this suggests a delayed/dropped message rather than normal jitter. */
        const val NETWORK_GAP_THRESHOLD_MS = 1_500L
    }
}
