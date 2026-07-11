package com.jp.paperplayer.party

import android.os.SystemClock
import android.util.Log
import com.jp.paperplayer.model.data.PartyMemberStatus
import com.jp.paperplayer.model.data.PartyMemberSyncStats
import com.jp.paperplayer.model.data.SyncHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auto-resync watchdog: a guest whose recalculated drift trend says it's
 * genuinely falling out of sync (not a single noisy sample — see
 * [SyncHealth]) gets forced back onto the timeline the same way a late
 * joiner does: fresh clock offset, pre-seek, scheduled start. Its own
 * incremental nudge/seek loop may not be enough to recover on its own if the
 * cause is a stale clock offset or a stuck player.
 *
 * Gated on [PartyFeatureFlags.HOST_ASSISTED_RESYNC_ENABLED] — off by
 * default. The guest's own nudge/seek loop chasing HOST_SYNC is the proven
 * baseline; this watchdog is a layer on top of it whose accuracy can only be
 * judged by ear, which made it hard to tune with confidence.
 */
class PartyHostResyncWatchdog(
    private val scope: CoroutineScope,
    private val songCatalog: PartyHostSongCatalog,
    private val syncStatsTracker: PartyHostSyncStatsTracker,
    private val setStatus: (memberId: String, status: PartyMemberStatus) -> Unit,
    private val publishMembers: () -> Unit,
    private val getHostPositionIfPlaying: suspend () -> Long?,
    private val sendToClient: (memberId: String, message: PartyMessage) -> Unit,
    private val isPreparing: () -> Boolean,
    private val registerSyncAck: (memberId: String) -> CompletableDeferred<Boolean>,
    private val clearSyncAck: (memberId: String) -> Unit,
) {
    // elapsedRealtime of the last START sent to each guest (party-wide,
    // late-join, or a targeted resync) — this watchdog won't act again until
    // RESYNC_GRACE_PERIOD_MS has passed, since every scheduled start has its
    // own few-second settling transient (host/guest start-latency warm-up,
    // nudge/seek corrections ramping down) that looks like "worsening" if
    // judged too early. This doubles as the resync rate limit, since a
    // resync itself is a start.
    private val lastStartedAtMs = mutableMapOf<String, Long>()

    /**
     * Gated on [RESYNC_GRACE_PERIOD_MS] since the guest's last START (party
     * start, late join, or a previous resync) rather than just on
     * [SyncHealth]: every scheduled start has its own few-second settling
     * transient, and judging that transient too early reads as "still
     * falling out of sync" — which resyncs again, which starts a new
     * transient, forever. This was firing resyncs back to back before the
     * previous settling attempt had a chance to finish.
     */
    fun check(memberId: String, deviceName: String, currentSongId: Long?, stats: PartyMemberSyncStats) {
        if (!PartyFeatureFlags.HOST_ASSISTED_RESYNC_ENABLED) return
        if (stats.syncHealth != SyncHealth.FALLING_OUT_OF_SYNC) return
        if (isPreparing()) {
            Log.d(TAG, "$deviceName: FALLING_OUT_OF_SYNC but host is preparing — skipping resync check")
            return
        }
        val songId = currentSongId ?: return
        val now = SystemClock.elapsedRealtime()
        val lastStarted = synchronized(lastStartedAtMs) { lastStartedAtMs[memberId] }
        val sinceStart = lastStarted?.let { now - it }
        if (lastStarted != null && sinceStart!! < RESYNC_GRACE_PERIOD_MS) {
            Log.d(
                TAG,
                "$deviceName: FALLING_OUT_OF_SYNC (verifiedDrift=${stats.verifiedDriftMs}ms) but started " +
                    "${sinceStart}ms ago, < ${RESYNC_GRACE_PERIOD_MS}ms grace — letting it settle instead of resyncing",
            )
            return
        }
        syncStatsTracker.incrementResyncCount(memberId)
        publishMembers()
        Log.i(
            TAG,
            "$deviceName: RESYNC #${stats.hostResyncCount + 1} triggered — verifiedDrift=${stats.verifiedDriftMs}ms " +
                "driftHistory=${stats.driftHistory} sinceLastStart=${sinceStart}ms",
        )
        scope.launch { resyncStragglingGuest(memberId, deviceName, songId) }
    }

    /**
     * Forces one guest back onto the announced timeline: a fresh SYNC_CHECK
     * (re-measures its clock offset, re-verifies the file, pre-seeks) then a
     * scheduled START — the same mechanism a late joiner goes through, but
     * triggered by detecting sustained drift instead of a fresh connection.
     * Scoped to this one client; the rest of the party, including the host's
     * own playback, is untouched.
     */
    private suspend fun resyncStragglingGuest(memberId: String, deviceName: String, songId: Long) {
        val position = getHostPositionIfPlaying() ?: run {
            Log.w(TAG, "$deviceName: resync aborted — host isn't playing")
            return
        }
        val sha256 = songCatalog.resolve(songId)?.let { songCatalog.sha256(it) } ?: ""
        setStatus(memberId, PartyMemberStatus.SYNCING)
        val ack = registerSyncAck(memberId)
        Log.i(TAG, "$deviceName: resync SYNC_CHECK sent, host position=${position}ms")
        sendToClient(memberId, PartyMessage.SyncCheck(songId, sha256, position))
        val ok = withTimeoutOrNull(SYNC_CHECK_TIMEOUT_MS) { ack.await() } ?: false
        clearSyncAck(memberId)
        if (!ok) {
            Log.w(TAG, "$deviceName: resync SYNC_CHECK failed or timed out after ${SYNC_CHECK_TIMEOUT_MS}ms")
            return
        }
        val freshPosition = getHostPositionIfPlaying() ?: run {
            Log.w(TAG, "$deviceName: resync aborted after SYNC_CHECK — host isn't playing")
            return
        }
        val at = SystemClock.elapsedRealtime() + RESUME_LEAD_MS
        Log.i(TAG, "$deviceName: resync START sent, position=${freshPosition + RESUME_LEAD_MS}ms at host clock $at")
        sendToClient(memberId, PartyMessage.Start(songId, freshPosition + RESUME_LEAD_MS, at))
        syncStatsTracker.clear(memberId)
        markStarted(memberId)
        setStatus(memberId, PartyMemberStatus.PLAYING)
    }

    /** Records that a guest was just sent a START — arms its settling grace period. */
    fun markStarted(memberId: String) {
        synchronized(lastStartedAtMs) { lastStartedAtMs[memberId] = SystemClock.elapsedRealtime() }
    }

    fun markStartedForAll(memberIds: Collection<String>) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lastStartedAtMs) { memberIds.forEach { lastStartedAtMs[it] = now } }
    }

    fun remove(memberId: String) {
        synchronized(lastStartedAtMs) { lastStartedAtMs.remove(memberId) }
    }

    private companion object {
        const val TAG = "PartyHostResyncWatchdog"

        /** Ceiling for the resync's own SYNC_CHECK round — same budget as the party-wide checklist. */
        const val SYNC_CHECK_TIMEOUT_MS = 10_000L

        /** Short lead for a resync start, matching a late joiner's; the party keeps playing. */
        const val RESUME_LEAD_MS = 1_500L

        /**
         * How long a guest gets to settle after any START (party start, late
         * join, or a previous resync) before the watchdog will act on it
         * again. Set past the ~10-20s natural convergence window observed
         * for the nudge/seek loop to lock on; shorter than that and the
         * watchdog catches normal settling mid-flight and mistakes it for a
         * persistent problem, re-triggering itself indefinitely.
         */
        const val RESYNC_GRACE_PERIOD_MS = 20_000L
    }
}
