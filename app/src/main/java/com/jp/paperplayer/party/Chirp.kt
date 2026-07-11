package com.jp.paperplayer.party

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Calibration chirps: short linear sine sweeps. The host plays the up-sweep,
 * the guest the down-sweep, so both are separable in one mic recording by
 * matched filtering. All DSP is pure Kotlin for unit-testability.
 */
object Chirp {

    const val SAMPLE_RATE = 44_100
    const val DURATION_MS = 150

    /** Gap between the host's scheduled chirp and the guest's own scheduled chirp. */
    const val OWN_CHIRP_DELAY_MS = 800L

    /**
     * Recording margin after the guest's own chirp, so its tail is never cut
     * off mid-capture. Generous margin: both chirps now play through a real
     * ExoPlayer ([ExoChirpPlayer]) rather than a bare AudioTrack, and its
     * decode + streamed-buffering pipeline has more, less predictable output
     * latency, so the actual chirp can land noticeably later than scheduled.
     */
    const val RECORD_TAIL_MS = 1_000L

    /** Detection runs on audio decimated by this factor (44.1kHz -> 11.025kHz). */
    const val DECIMATION = 4
    const val DETECT_SAMPLE_RATE = SAMPLE_RATE / DECIMATION

    private const val AMPLITUDE = 0.6
    private const val FADE_MS = 5

    /** A sweep's low/high edge in Hz; the host sweeps low->high, the guest high->low. */
    data class Band(val lowHz: Double, val highHz: Double)

    private val DEFAULT_BAND = Band(2_000.0, 4_000.0)

    /**
     * Frequency bands swept across one calibration run. Spread across the
     * range most phone speakers/mics reproduce reasonably flat (~1-7kHz) so a
     * resonance, mic rolloff, or narrowband noise source at any single
     * frequency doesn't bias the whole measurement — each band yields an
     * independent trim estimate and the caller takes the median.
     */
    val BANDS = listOf(
        Band(1_000.0, 2_000.0),
        Band(1_600.0, 3_000.0),
        DEFAULT_BAND,
        Band(3_000.0, 5_200.0),
        Band(4_200.0, 7_000.0),
    )

    /** Host chirp: low -> high sweep, 16-bit PCM at [SAMPLE_RATE]. */
    fun hostChirpPcm(band: Band = DEFAULT_BAND): ShortArray = generate(band.lowHz, band.highHz, SAMPLE_RATE)

    /** Guest chirp: high -> low sweep, 16-bit PCM at [SAMPLE_RATE]. */
    fun guestChirpPcm(band: Band = DEFAULT_BAND): ShortArray = generate(band.highHz, band.lowHz, SAMPLE_RATE)

    /** Detection template for the host chirp at the decimated rate. */
    fun hostTemplate(band: Band = DEFAULT_BAND): FloatArray = toFloats(generate(band.lowHz, band.highHz, DETECT_SAMPLE_RATE))

    /** Detection template for the guest chirp at the decimated rate. */
    fun guestTemplate(band: Band = DEFAULT_BAND): FloatArray = toFloats(generate(band.highHz, band.lowHz, DETECT_SAMPLE_RATE))

    private fun generate(fromHz: Double, toHz: Double, sampleRate: Int): ShortArray {
        val n = sampleRate * DURATION_MS / 1000
        val durationS = DURATION_MS / 1000.0
        val fadeSamples = sampleRate * FADE_MS / 1000
        val samples = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            // Linear chirp phase: 2π (f0 t + (f1-f0) t² / 2T)
            val phase = 2.0 * PI * (fromHz * t + (toHz - fromHz) * t * t / (2.0 * durationS))
            val fade = min(1.0, min(i, n - 1 - i).toDouble() / fadeSamples)
            samples[i] = (sin(phase) * fade * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    private fun toFloats(pcm: ShortArray): FloatArray =
        FloatArray(pcm.size) { pcm[it].toFloat() / Short.MAX_VALUE }

    /** Averages groups of [DECIMATION] samples; cheap anti-aliased downsample. */
    fun decimate(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size / DECIMATION)
        for (i in out.indices) {
            var sum = 0f
            val base = i * DECIMATION
            for (j in 0 until DECIMATION) sum += pcm[base + j].toFloat()
            out[i] = sum / (DECIMATION * Short.MAX_VALUE)
        }
        return out
    }

    /**
     * Matched-filter peak search: index in [signal] (inclusive range
     * [searchFrom]..[searchTo]) where [template] correlates strongest, or null
     * when the peak does not stand out from the surrounding correlation floor.
     */
    fun findChirp(signal: FloatArray, template: FloatArray, searchFrom: Int, searchTo: Int): Int? {
        val from = searchFrom.coerceAtLeast(0)
        val to = searchTo.coerceAtMost(signal.size - template.size)
        if (from > to) return null

        var bestIndex = -1
        var bestValue = 0f
        var sumAbs = 0.0
        var count = 0
        for (lag in from..to) {
            var corr = 0f
            for (i in template.indices) corr += signal[lag + i] * template[i]
            val magnitude = kotlin.math.abs(corr)
            if (magnitude > bestValue) {
                bestValue = magnitude
                bestIndex = lag
            }
            sumAbs += magnitude
            count++
        }
        if (bestIndex < 0 || count == 0) return null
        val floor = (sumAbs / count).toFloat()
        // A real chirp towers over the correlation floor; noise does not.
        return if (bestValue > floor * PEAK_TO_FLOOR_RATIO) bestIndex else null
    }

    private const val PEAK_TO_FLOOR_RATIO = 5f
}
