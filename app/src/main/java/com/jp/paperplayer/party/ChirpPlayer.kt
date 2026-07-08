package com.jp.paperplayer.party

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

/**
 * Plays a calibration chirp at a scheduled elapsedRealtime instant. Whatever
 * scheduling and pipeline latency remains after the spin-wait is exactly the
 * per-device output latency the calibration measures — so no compensation here.
 */
object ChirpPlayer {

    fun playAt(pcm: ShortArray, atElapsedMs: Long) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Chirp.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            val wait = atElapsedMs - SystemClock.elapsedRealtime()
            if (wait > 0) SystemClock.sleep(wait - 2)
            @Suppress("ControlFlowWithEmptyBody")
            while (SystemClock.elapsedRealtime() < atElapsedMs) { }
            track.play()
            // Let the chirp finish before releasing the track.
            SystemClock.sleep(Chirp.DURATION_MS + 100L)
        } catch (e: Exception) {
            Log.w(TAG, "Chirp playback failed: ${e.message}")
        } finally {
            runCatching { track.release() }
        }
    }

    private const val TAG = "ChirpPlayer"
}
