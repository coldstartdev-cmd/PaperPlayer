package com.jp.paperplayer.party

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Guest side of acoustic latency calibration: requests one chirp round per
 * [Chirp.BANDS] frequency band and takes the median of the successful
 * measurements. A single band's chirp pair can get thrown off by a
 * speaker/mic resonance or ambient noise sitting right in that band;
 * sweeping several bands and combining them makes the result robust to any
 * one bad measurement.
 */
class PartyGuestCalibration(
    private val context: Context,
    private val send: (PartyMessage) -> Unit,
    private val clockOffsetMs: () -> Long?,
) {
    // Written on the caller's thread (calibrate() hops onto its own IO
    // context, but the readLoop that delivers the reply is a separate IO
    // thread) so this needs @Volatile for cross-thread visibility.
    @Volatile
    private var pendingCalibration: CompletableDeferred<PartyMessage?>? = null

    /**
     * Returns the median trim if at least one band succeeded, otherwise the
     * last failure's reason. Caller must already hold RECORD_AUDIO.
     */
    suspend fun calibrate(onProgress: (String) -> Unit = {}): LatencyCalibrator.Result =
        withContext(Dispatchers.IO) {
            val results = Chirp.BANDS.mapIndexed { index, band ->
                onProgress("Listening… hold the devices together (${index + 1}/${Chirp.BANDS.size})")
                val result = calibrateBand(index, band)
                when (result) {
                    is LatencyCalibrator.Result.Success ->
                        Log.d(TAG, "Band $band -> ${result.trimMs}ms")
                    is LatencyCalibrator.Result.Failure ->
                        Log.d(TAG, "Band $band -> failed: ${result.reason}")
                }
                result
            }
            val trims = results.filterIsInstance<LatencyCalibrator.Result.Success>().map { it.trimMs }
            if (trims.isEmpty()) {
                val reason = results.filterIsInstance<LatencyCalibrator.Result.Failure>()
                    .lastOrNull()?.reason ?: "Calibration failed — try again"
                Log.d(TAG, "Calibration: 0/${results.size} bands succeeded")
                LatencyCalibrator.Result.Failure(reason)
            } else {
                val median = median(trims)
                Log.d(TAG, "Calibration: $trims -> median ${median}ms (${trims.size}/${results.size} bands succeeded)")
                LatencyCalibrator.Result.Success(median)
            }
        }

    /**
     * One request/chirp/measure round for a single frequency band.
     *
     * Called from [calibrate], which already runs on IO, unlike everything
     * else here which is dispatched onto it explicitly — so this can touch
     * the socket directly. Blocking socket I/O on Main throws
     * NetworkOnMainThreadException, which was getting silently swallowed by
     * the runCatching around send(), meaning the request never actually
     * left the device.
     */
    private suspend fun calibrateBand(bandIndex: Int, band: Chirp.Band): LatencyCalibrator.Result {
        val deferred = CompletableDeferred<PartyMessage?>()
        pendingCalibration = deferred
        runCatching { send(PartyMessage.CalibrateRequest(bandIndex)) }
            .onFailure { Log.w(TAG, "Calibrate request failed to send: ${it.message}") }
        val reply = withTimeoutOrNull(CALIBRATE_REPLY_TIMEOUT_MS) { deferred.await() }
        pendingCalibration = null
        return when (reply) {
            is PartyMessage.CalibrateChirp -> {
                val offset = clockOffsetMs() ?: 0L
                val hostChirpLocal = reply.atHostElapsedMs - offset
                val result = LatencyCalibrator(context).measure(
                    hostChirpAtLocalMs = hostChirpLocal,
                    ownChirpAtLocalMs = hostChirpLocal + Chirp.OWN_CHIRP_DELAY_MS,
                    band = band,
                )
                // Tell the host this round is actually done — it's waiting on
                // this instead of guessing our timing, so the next band's
                // request doesn't get denied as "still busy".
                runCatching { send(PartyMessage.CalibrateComplete(bandIndex)) }
                result
            }
            is PartyMessage.CalibrateDenied -> LatencyCalibrator.Result.Failure(reply.reason)
            else -> LatencyCalibrator.Result.Failure("The host didn't respond — try again")
        }
    }

    /** Called from the engine's readLoop `when` for CALIBRATE_CHIRP/CALIBRATE_DENIED replies. */
    fun onReply(message: PartyMessage) {
        pendingCalibration?.complete(message)
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private companion object {
        const val TAG = "PartyGuestCalibration"
        const val CALIBRATE_REPLY_TIMEOUT_MS = 5_000L
    }
}
