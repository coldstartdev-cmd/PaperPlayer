package com.jp.paperplayer.party

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates the host side of acoustic latency calibration: emits the
 * host's chirp at an announced instant so the requesting guest can measure
 * relative output latency, and gates concurrent requests to one at a time
 * (the room needs to be quiet for exactly one chirp pair). [pauseHostPlayback]
 * must be a plain player pause — not the engine's own suppressed
 * "enginePause" — since the point is for the party's normal playback
 * listener to notice and broadcast the pause to every guest, keeping the
 * room quiet during the chirp.
 */
class PartyHostCalibrationCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pauseHostPlayback: () -> Unit,
    private val sendToClient: (memberId: String, message: PartyMessage) -> Unit,
) {
    private var calibrating = false
    private var calibratingMemberId: String? = null
    private var calibratingBandIndex = -1
    private var calibrateCompleteAck: CompletableDeferred<Unit>? = null

    fun onCalibrateRequest(memberId: String, bandIndex: Int) {
        val ack = CompletableDeferred<Unit>()
        synchronized(this) {
            if (calibrating) {
                sendToClient(memberId, PartyMessage.CalibrateDenied("Another device is calibrating — try again in a moment"))
                return
            }
            calibrating = true
            calibratingMemberId = memberId
            calibratingBandIndex = bandIndex
            calibrateCompleteAck = ack
        }
        scope.launch {
            try {
                val band = Chirp.BANDS.getOrElse(bandIndex) { Chirp.BANDS[0] }
                withContext(Dispatchers.Main) { pauseHostPlayback() }
                val at = SystemClock.elapsedRealtime() + CALIBRATE_LEAD_MS
                sendToClient(memberId, PartyMessage.CalibrateChirp(at, bandIndex))
                ExoChirpPlayer.playAt(context, Chirp.hostChirpPcm(band), Chirp.SAMPLE_RATE, at)
                // Wait for the guest's own CALIBRATE_COMPLETE instead of
                // guessing how long its chirp-and-record round takes — a
                // fixed estimate here previously raced the guest's actual
                // timing under real-world jitter (GC pauses, AudioRecord
                // setup variance, WiFi retransmits). The timeout is a safety
                // net only, for a guest that disconnects mid-round.
                withTimeoutOrNull(CALIBRATE_COMPLETE_TIMEOUT_MS) { ack.await() }
            } finally {
                synchronized(this@PartyHostCalibrationCoordinator) {
                    calibrating = false
                    calibratingMemberId = null
                    calibratingBandIndex = -1
                    calibrateCompleteAck = null
                }
            }
        }
    }

    /**
     * Guest -> host: its calibration round is done. Matched on both member
     * and band so a late/stray ack from a different guest's earlier round
     * can't complete an unrelated in-flight one that happens to reuse the
     * same band index.
     */
    fun onCalibrateComplete(memberId: String, bandIndex: Int) {
        synchronized(this) {
            if (memberId == calibratingMemberId && bandIndex == calibratingBandIndex) {
                calibrateCompleteAck?.complete(Unit)
            }
        }
    }

    private companion object {
        /** Lead time announced before the host's calibration chirp. */
        const val CALIBRATE_LEAD_MS = 1_500L

        /** Safety net only: releases the busy lock even if a guest's CALIBRATE_COMPLETE never arrives. */
        const val CALIBRATE_COMPLETE_TIMEOUT_MS = 5_000L
    }
}
