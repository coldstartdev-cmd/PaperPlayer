package com.jp.paperplayer.party

import android.os.SystemClock
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.model.ui.SyncQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NTP-style clock-offset estimation for the guest: runs ping/pong rounds
 * over the TCP control socket (via [send]/[onPong]) and delegates the actual
 * offset math to the pure-function [ClockSync]. [runPingRound] is also
 * called directly (not just from the periodic [start] loop) as part of the
 * pre-start SYNC_CHECK re-measurement, so it stays a public suspend
 * function rather than a private implementation detail of the loop.
 */
class PartyGuestClockSync(
    private val send: (PartyMessage) -> Unit,
    private val update: ((PartyUiState) -> PartyUiState) -> Unit,
) {
    /** hostClock - guestClock; null until the first ping round completes. */
    @Volatile
    var clockOffsetMs: Long? = null
        private set

    @Volatile
    var lastMedianRttMs: Long = 0L
        private set

    private var pingSeq = 0

    // Serializes ping rounds: the periodic refresh and a pre-start sync check
    // must not interleave their samples.
    private val pingMutex = Mutex()
    private val pendingSamples = mutableListOf<PingSample>()

    /** Launches the periodic ping-round loop (join round, then refreshed every [OFFSET_REFRESH_INTERVAL_MS]). */
    fun start(scope: CoroutineScope) {
        scope.launch { pingRounds() }
    }

    private suspend fun pingRounds() {
        runPingRound(ClockSync.JOIN_PING_COUNT)
        while (true) {
            delay(OFFSET_REFRESH_INTERVAL_MS)
            runPingRound(ClockSync.REFRESH_PING_COUNT)
        }
    }

    suspend fun runPingRound(count: Int) = pingMutex.withLock {
        synchronized(pendingSamples) { pendingSamples.clear() }
        repeat(count) {
            runCatching { send(PartyMessage.Ping(++pingSeq, SystemClock.elapsedRealtime())) }
            delay(ClockSync.PING_INTERVAL_MS)
        }
        // Grace period for the last replies to arrive.
        delay(500L)
        val samples = synchronized(pendingSamples) { pendingSamples.toList() }
        if (samples.isEmpty()) return
        clockOffsetMs = ClockSync.estimateOffsetMs(samples)
        val quality = if (ClockSync.isQualityPoor(samples)) SyncQuality.POOR else SyncQuality.GOOD
        val medianRtt = ClockSync.medianRttMs(samples)
        lastMedianRttMs = medianRtt ?: 0L
        update {
            it.copy(
                syncQuality = quality,
                rttMs = medianRtt,
                syncDebug = it.syncDebug.copy(clockOffsetMs = clockOffsetMs, medianRttMs = medianRtt),
            )
        }
    }

    fun onPong(pong: PartyMessage.Pong) {
        val sample = PingSample(t0 = pong.t0, t1 = pong.t1, t2 = SystemClock.elapsedRealtime())
        synchronized(pendingSamples) { pendingSamples.add(sample) }
    }

    /**
     * A brand-new join (or a reconnect after a fresh process start) can have
     * its cached file pass the download/hash check almost instantly, so the
     * host's late-join START can arrive before the very first ping round
     * (JOIN_PING_COUNT samples, ~1.7s) has produced a clock offset. Falling
     * back to an offset of 0 would treat the host's elapsedRealtime as if it
     * were this device's own — elapsedRealtime is boot-relative, so the two
     * can differ by any amount, and the guest ends up targeting a garbage
     * instant (never starts, or seeks somewhere nonsensical). Wait briefly
     * for the in-flight ping round instead.
     */
    suspend fun awaitOffset(): Long {
        clockOffsetMs?.let { return it }
        val deadline = SystemClock.elapsedRealtime() + CLOCK_OFFSET_WAIT_TIMEOUT_MS
        while (clockOffsetMs == null && SystemClock.elapsedRealtime() < deadline) {
            delay(50L)
        }
        return clockOffsetMs ?: 0L
    }

    private companion object {
        const val OFFSET_REFRESH_INTERVAL_MS = 30_000L

        /** Ceiling for schedulePlay to wait for the in-flight join ping round (~1.7s worst case). */
        const val CLOCK_OFFSET_WAIT_TIMEOUT_MS = 3_000L
    }
}
