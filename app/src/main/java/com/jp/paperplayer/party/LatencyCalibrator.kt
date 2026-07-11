package com.jp.paperplayer.party

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Measures the guest's audio output latency relative to the host, acoustically:
 * the host emits an up-sweep chirp at a known instant, this device emits a
 * down-sweep 800ms later, and one continuous mic recording captures both.
 * Both chirps play through a real ExoPlayer ([ExoChirpPlayer]) rather than a
 * bare AudioTrack, so the measured latency reflects the actual party-music
 * pipeline (decode + streamed buffering) instead of a differently-configured
 * proxy that doesn't transfer to real playback. Input latency cancels because
 * both chirps pass through the same mic chain, and acoustic travel is
 * ~1ms/30cm with the devices held together. See [detect] for the sign of the
 * resulting trim, which is empirically inverted from the straightforward
 * (interval - scheduledGap) derivation.
 *
 * Caller must hold the RECORD_AUDIO permission.
 */
class LatencyCalibrator(private val context: Context) {

    sealed class Result {
        data class Success(val trimMs: Long) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * [hostChirpAtLocalMs] / [ownChirpAtLocalMs]: elapsedRealtime instants for
     * the host's chirp (already clock-converted) and this device's own chirp,
     * both swept in [band].
     */
    @SuppressLint("MissingPermission")
    suspend fun measure(
        hostChirpAtLocalMs: Long,
        ownChirpAtLocalMs: Long,
        band: Chirp.Band,
    ): Result = coroutineScope {
        val recordAheadMs = 400L
        val totalMs = (ownChirpAtLocalMs - hostChirpAtLocalMs) + recordAheadMs + Chirp.RECORD_TAIL_MS
        val totalSamples = (Chirp.SAMPLE_RATE * totalMs / 1000L).toInt()

        val minBuffer = AudioRecord.getMinBufferSize(
            Chirp.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Chirp.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, Chirp.SAMPLE_RATE), // >= 0.5s of headroom
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@coroutineScope Result.Failure("Microphone unavailable")
        }

        val pcm = ShortArray(totalSamples)
        try {
            // Start recording just ahead of the host chirp.
            val startAt = hostChirpAtLocalMs - recordAheadMs
            val preWait = startAt - SystemClock.elapsedRealtime()
            if (preWait > 0) kotlinx.coroutines.delay(preWait)
            record.startRecording()
            val recordingStartedAt = SystemClock.elapsedRealtime()

            // Own chirp plays in parallel while the read loop below fills the buffer.
            launch {
                ExoChirpPlayer.playAt(context, Chirp.guestChirpPcm(band), Chirp.SAMPLE_RATE, ownChirpAtLocalMs)
            }

            withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < totalSamples) {
                    val read = record.read(pcm, offset, totalSamples - offset)
                    if (read <= 0) break
                    offset += read
                }
            }

            withContext(Dispatchers.Default) {
                detect(pcm, recordingStartedAt, hostChirpAtLocalMs, ownChirpAtLocalMs, band)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Calibration failed: ${e.message}")
            Result.Failure("Calibration failed — try again")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun detect(
        pcm: ShortArray,
        recordingStartedAt: Long,
        hostChirpAtLocalMs: Long,
        ownChirpAtLocalMs: Long,
        band: Chirp.Band,
    ): Result {
        val signal = Chirp.decimate(pcm)
        val rate = Chirp.DETECT_SAMPLE_RATE

        fun expectedIndex(atMs: Long): Int = ((atMs - recordingStartedAt) * rate / 1000L).toInt()
        val windowSamples = rate * SEARCH_WINDOW_MS / 1000

        val hostExpected = expectedIndex(hostChirpAtLocalMs)
        val hostIndex = Chirp.findChirp(
            signal, Chirp.hostTemplate(band),
            hostExpected - windowSamples, hostExpected + windowSamples,
        ) ?: return Result.Failure("Couldn't hear the host — raise its media volume and hold the devices together")

        val ownExpected = expectedIndex(ownChirpAtLocalMs)
        val ownIndex = Chirp.findChirp(
            signal, Chirp.guestTemplate(band),
            ownExpected - windowSamples, ownExpected + windowSamples,
        ) ?: return Result.Failure("Couldn't hear this device — raise its media volume")

        val intervalMs = (ownIndex - hostIndex) * 1000L / rate
        val scheduledGapMs = ownChirpAtLocalMs - hostChirpAtLocalMs
        // The derivation says (interval - scheduledGap) = guestOutputLatency -
        // hostOutputLatency, which is exactly the trim's sign convention — but
        // that comes out inverted in practice (confirmed against a device's
        // known-good ear-tuned trim), most likely because the up-sweep and
        // down-sweep templates don't land symmetrically under the matched
        // filter once the recording is decimated. Negate it to match reality
        // until the asymmetry itself is tracked down.
        val trimMs = -(intervalMs - scheduledGapMs)
        Log.d(TAG, "Chirps at $hostIndex/$ownIndex, interval ${intervalMs}ms, trim ${trimMs}ms")

        return if (trimMs in -MAX_TRIM_MS..MAX_TRIM_MS) {
            Result.Success(trimMs)
        } else {
            Result.Failure("Measurement out of range (${trimMs}ms) — try again in a quieter room")
        }
    }

    private companion object {
        const val TAG = "LatencyCalibrator"

        // Widened from the bare-AudioTrack version's 350ms: ExoPlayer's
        // decode + streamed-buffering pipeline has more (and less
        // predictable) output latency than a MODE_STATIC AudioTrack, so the
        // chirp can legitimately land further from its scheduled instant.
        const val SEARCH_WINDOW_MS = 450
        const val MAX_TRIM_MS = 300L
    }
}
