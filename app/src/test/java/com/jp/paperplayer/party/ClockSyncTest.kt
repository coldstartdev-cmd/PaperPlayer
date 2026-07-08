package com.jp.paperplayer.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {

    // A symmetric round trip: guest sends at t0, host is `offset` ahead,
    // one-way latency is `lat` each direction.
    private fun sample(t0: Long, offset: Long, lat: Long) =
        PingSample(t0 = t0, t1 = t0 + lat + offset, t2 = t0 + 2 * lat)

    @Test
    fun `offset recovered exactly under symmetric latency`() {
        val samples = (0L until 8L).map { i -> sample(t0 = i * 150, offset = 1_234, lat = 5) }
        assertEquals(1_234L, ClockSync.estimateOffsetMs(samples))
    }

    @Test
    fun `negative offset recovered when guest clock is ahead`() {
        val samples = (0L until 8L).map { i -> sample(t0 = i * 150, offset = -777, lat = 3) }
        assertEquals(-777L, ClockSync.estimateOffsetMs(samples))
    }

    @Test
    fun `high-rtt outliers are discarded`() {
        val good = (0L until 6L).map { i -> sample(t0 = i * 150, offset = 100, lat = 4) }
        // Two congested round trips whose asymmetric delay poisons the offset.
        val bad = listOf(
            PingSample(t0 = 900, t1 = 900 + 400 + 100, t2 = 900 + 450),
            PingSample(t0 = 1050, t1 = 1050 + 5 + 100, t2 = 1050 + 500),
        )
        assertEquals(100L, ClockSync.estimateOffsetMs(good + bad))
    }

    @Test
    fun `empty samples yield null`() {
        assertNull(ClockSync.estimateOffsetMs(emptyList()))
        assertNull(ClockSync.medianRttMs(emptyList()))
    }

    @Test
    fun `median rtt over odd and even counts`() {
        val samples = listOf(
            sample(0, 0, 2),   // rtt 4
            sample(150, 0, 5), // rtt 10
            sample(300, 0, 3), // rtt 6
        )
        assertEquals(6L, ClockSync.medianRttMs(samples))
        assertEquals(7L, ClockSync.medianRttMs(samples.take(2)))
    }

    @Test
    fun `quality good on a quiet lan`() {
        val samples = (0L until 8L).map { i -> sample(t0 = i * 150, offset = 50, lat = 4) }
        assertFalse(ClockSync.isQualityPoor(samples))
    }

    @Test
    fun `quality poor when median rtt is high`() {
        val samples = (0L until 8L).map { i -> sample(t0 = i * 150, offset = 50, lat = 80) }
        assertTrue(ClockSync.isQualityPoor(samples))
    }

    @Test
    fun `quality poor when jitter is high`() {
        val samples = (0L until 8L).map { i ->
            sample(t0 = i * 150, offset = 50, lat = if (i % 2 == 0L) 2 else 60)
        }
        assertTrue(ClockSync.isQualityPoor(samples))
    }

    @Test
    fun `quality poor with no samples`() {
        assertTrue(ClockSync.isQualityPoor(emptyList()))
    }
}
