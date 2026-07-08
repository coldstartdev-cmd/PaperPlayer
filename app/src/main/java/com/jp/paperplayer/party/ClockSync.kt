package com.jp.paperplayer.party

/**
 * One ping round-trip: [t0] guest send time, [t1] host receive time,
 * [t2] guest receive time — t0/t2 on the guest clock, t1 on the host clock
 * (both SystemClock.elapsedRealtime).
 */
data class PingSample(val t0: Long, val t1: Long, val t2: Long) {
    val rttMs: Long get() = t2 - t0

    /** hostClock - guestClock, assuming symmetric network latency. */
    val offsetMs: Long get() = t1 - (t0 + t2) / 2
}

/**
 * NTP-style clock offset estimation over the LAN control socket.
 * Pure functions so the math is unit-testable without Android.
 */
object ClockSync {

    const val JOIN_PING_COUNT = 8
    const val REFRESH_PING_COUNT = 4
    const val PING_INTERVAL_MS = 150L

    private const val RTT_FILTER_FACTOR = 1.5
    private const val POOR_MEDIAN_RTT_MS = 100L
    private const val POOR_JITTER_MS = 50L

    /**
     * Median offset of the samples whose RTT is within [RTT_FILTER_FACTOR] of the
     * best RTT seen — high-RTT samples had asymmetric queuing and poison the estimate.
     * Null when there are no samples.
     */
    fun estimateOffsetMs(samples: List<PingSample>): Long? {
        if (samples.isEmpty()) return null
        val minRtt = samples.minOf { it.rttMs }
        val survivors = samples.filter { it.rttMs <= minRtt * RTT_FILTER_FACTOR }
        return median(survivors.map { it.offsetMs })
    }

    fun medianRttMs(samples: List<PingSample>): Long? =
        if (samples.isEmpty()) null else median(samples.map { it.rttMs })

    /**
     * Quality gate for the join-time compatibility check: poor when the median RTT
     * is high or the spread between typical and best RTT (jitter) is large.
     */
    fun isQualityPoor(samples: List<PingSample>): Boolean {
        if (samples.isEmpty()) return true
        val median = medianRttMs(samples) ?: return true
        val minRtt = samples.minOf { it.rttMs }
        val p90 = percentile90(samples.map { it.rttMs })
        return median > POOR_MEDIAN_RTT_MS || (p90 - minRtt) > POOR_JITTER_MS
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun percentile90(values: List<Long>): Long {
        val sorted = values.sorted()
        val index = ((sorted.size * 9) / 10 - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
