package com.jp.paperplayer.party

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChirpTest {

    /** Synthesizes a decimated-rate signal with chirp templates buried at known offsets. */
    private fun signalWith(vararg placements: Pair<FloatArray, Int>, lengthSamples: Int, noise: Float = 0.01f): FloatArray {
        val random = Random(42)
        val signal = FloatArray(lengthSamples) { (random.nextFloat() - 0.5f) * 2f * noise }
        placements.forEach { (template, at) ->
            template.forEachIndexed { i, sample ->
                if (at + i < lengthSamples) signal[at + i] += sample * 0.5f
            }
        }
        return signal
    }

    @Test
    fun `finds a chirp at the planted position`() {
        val template = Chirp.hostTemplate()
        val signal = signalWith(template to 4_000, lengthSamples = 20_000)
        val found = Chirp.findChirp(signal, template, 2_000, 8_000)
        assertNotNull(found)
        assertTrue("expected ~4000, got $found", abs(found!! - 4_000) <= 2)
    }

    @Test
    fun `separates host and guest chirps in one recording`() {
        val host = Chirp.hostTemplate()
        val guest = Chirp.guestTemplate()
        val hostAt = 3_000
        val guestAt = 3_000 + Chirp.DETECT_SAMPLE_RATE * 800 / 1000 // 800ms later
        val signal = signalWith(host to hostAt, guest to guestAt, lengthSamples = 16_000)

        val foundHost = Chirp.findChirp(signal, host, hostAt - 1_000, hostAt + 1_000)
        val foundGuest = Chirp.findChirp(signal, guest, guestAt - 1_000, guestAt + 1_000)
        assertNotNull(foundHost)
        assertNotNull(foundGuest)

        val intervalMs = (foundGuest!! - foundHost!!) * 1000L / Chirp.DETECT_SAMPLE_RATE
        assertTrue("interval $intervalMs should be ~800ms", abs(intervalMs - 800) <= 2)
    }

    @Test
    fun `simulated output latency shows up as the trim`() {
        val host = Chirp.hostTemplate()
        val guest = Chirp.guestTemplate()
        val latencyMs = 60
        val hostAt = 3_000
        val guestAt = hostAt + Chirp.DETECT_SAMPLE_RATE * (800 + latencyMs) / 1000
        val signal = signalWith(host to hostAt, guest to guestAt, lengthSamples = 16_000)

        val foundHost = Chirp.findChirp(signal, host, hostAt - 1_000, hostAt + 1_000)!!
        val foundGuest = Chirp.findChirp(signal, guest, guestAt - 1_000, guestAt + 1_000)!!
        val trimMs = (foundGuest - foundHost) * 1000L / Chirp.DETECT_SAMPLE_RATE - 800
        assertTrue("expected ~${latencyMs}ms, got $trimMs", abs(trimMs - latencyMs) <= 2)
    }

    @Test
    fun `pure noise yields no detection`() {
        val template = Chirp.hostTemplate()
        val signal = signalWith(lengthSamples = 20_000, noise = 0.3f)
        assertNull(Chirp.findChirp(signal, template, 0, 15_000))
    }

    @Test
    fun `decimation preserves chirp position`() {
        val fullRate = Chirp.hostChirpPcm()
        val lead = ShortArray(Chirp.SAMPLE_RATE / 2) // 500ms of silence
        val recording = lead + fullRate + ShortArray(Chirp.SAMPLE_RATE / 4)
        val signal = Chirp.decimate(recording)
        val expectedAt = lead.size / Chirp.DECIMATION

        val found = Chirp.findChirp(signal, Chirp.hostTemplate(), 0, signal.size)
        assertNotNull(found)
        assertTrue("expected ~$expectedAt, got $found", abs(found!! - expectedAt) <= 3)
    }

    @Test
    fun `templates render at the expected length`() {
        val samples = Chirp.DETECT_SAMPLE_RATE * Chirp.DURATION_MS / 1000
        assertEquals(samples, Chirp.hostTemplate().size)
        assertEquals(samples, Chirp.guestTemplate().size)
    }
}
