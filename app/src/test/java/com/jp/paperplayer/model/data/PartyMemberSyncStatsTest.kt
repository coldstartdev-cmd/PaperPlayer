package com.jp.paperplayer.model.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PartyMemberSyncStatsTest {

    private fun stats(driftMs: Long, driftHistory: List<Long>) = PartyMemberSyncStats(
        deviceAtMs = 0L,
        hostAtMs = 0L,
        expectedPositionMs = 0L,
        actualPositionMs = driftMs,
        rttMs = 0L,
        playbackSpeed = 1f,
        seekCorrections = 0,
        nudgeCorrections = 0,
        driftHistory = driftHistory,
    )

    @Test
    fun `drift and clock offset are derived from the raw timestamps`() {
        val result = PartyMemberSyncStats(
            deviceAtMs = 10_000L,
            hostAtMs = 9_950L,
            expectedPositionMs = 63_400L,
            actualPositionMs = 63_418L,
            rttMs = 12L,
            playbackSpeed = 1f,
            seekCorrections = 0,
            nudgeCorrections = 0,
        )
        assertEquals(18L, result.driftMs)
        assertEquals(-50L, result.clockOffsetMs)
    }

    @Test
    fun `verified drift overrides the guest's self-reported drift when available`() {
        val result = PartyMemberSyncStats(
            deviceAtMs = 0L,
            hostAtMs = 0L,
            expectedPositionMs = 63_400L, // guest's own (stale) view
            actualPositionMs = 63_410L,
            rttMs = 0L,
            playbackSpeed = 1f,
            seekCorrections = 0,
            nudgeCorrections = 0,
            hostVerifiedExpectedPositionMs = 63_300L, // host's authoritative recompute
        )
        assertEquals(10L, result.driftMs) // what the guest itself thinks
        assertEquals(110L, result.verifiedDriftMs) // what's actually true
    }

    @Test
    fun `syncHealth trend reacts to the recalculated drift, not the guest's self-report`() {
        // The guest reports a small, stable drift throughout — its own
        // (stale) view would call this fine. The host's recompute says the
        // opposite: a real worsening trend, well past what the guest's own
        // nudge loop quietly handles on its own.
        val history = List(20) { i -> if (i < 10) 20L + i * 3L else 150L + (i - 10) * 10L }
        val result = PartyMemberSyncStats(
            deviceAtMs = 0L,
            hostAtMs = 0L,
            expectedPositionMs = 63_400L,
            actualPositionMs = 63_402L, // guest thinks it's 2ms off
            rttMs = 0L,
            playbackSpeed = 1f,
            seekCorrections = 0,
            nudgeCorrections = 0,
            driftHistory = history,
            hostVerifiedExpectedPositionMs = 63_402L - history.last(),
        )
        assertEquals(2L, result.driftMs)
        assertEquals(SyncHealth.FALLING_OUT_OF_SYNC, result.syncHealth)
    }

    @Test
    fun `small drift with too little history is in sync`() {
        val result = stats(driftMs = 3L, driftHistory = listOf(1L, 2L, 3L))
        assertEquals(SyncHealth.IN_SYNC, result.syncHealth)
    }

    @Test
    fun `large drift with too little history is drifting`() {
        val result = stats(driftMs = 60L, driftHistory = listOf(50L, 55L, 60L))
        assertEquals(SyncHealth.DRIFTING, result.syncHealth)
    }

    @Test
    fun `stable small drift over a full window is in sync`() {
        val history = List(20) { (it % 5) - 2L } // oscillates -2..2, no trend
        assertEquals(SyncHealth.IN_SYNC, stats(driftMs = 2L, driftHistory = history).syncHealth)
    }

    @Test
    fun `stable but elevated drift is drifting, not falling out of sync`() {
        val history = List(20) { 30L + (it % 5) } // steady ~30-34, no worsening trend
        assertEquals(SyncHealth.DRIFTING, stats(driftMs = 34L, driftHistory = history).syncHealth)
    }

    @Test
    fun `drift trending upward across the window is falling out of sync`() {
        // Older half ~20-47, newer half ~150-240: a real worsening trend
        // that's outgrown the guest's own seek threshold, not just noise
        // the guest's nudge loop is already quietly absorbing.
        val history = List(20) { i -> if (i < 10) 20L + i * 3L else 150L + (i - 10) * 10L }
        assertEquals(SyncHealth.FALLING_OUT_OF_SYNC, stats(driftMs = 240L, driftHistory = history).syncHealth)
    }

    @Test
    fun `a brief spike that settles back down is not falling out of sync`() {
        // Recent half average is back down near the older half's — the trend
        // reversed, so this shouldn't still read as "worsening".
        val history = List(20) { i -> if (i < 10) 5L + i else 20L - i } // spikes then recovers
        val result = stats(driftMs = history.last(), driftHistory = history)
        assert(result.syncHealth != SyncHealth.FALLING_OUT_OF_SYNC) {
            "expected recovered drift not to read as falling out of sync, got ${result.syncHealth}"
        }
    }
}
