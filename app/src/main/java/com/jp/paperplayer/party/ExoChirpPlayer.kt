package com.jp.paperplayer.party

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays a calibration chirp through a real ExoPlayer instance — the same
 * player class, default AudioAttributes, and MediaCodec-decode + streamed
 * AudioTrack pipeline that plays actual party music — instead of a bare
 * AudioTrack (the previous approach). A calibration measured against a
 * differently-configured pipeline (different buffering, content type, no
 * decode step) doesn't necessarily transfer to the latency real playback
 * experiences.
 *
 * Builds a throwaway ExoPlayer rather than reusing the party's live
 * MediaController: that avoids interrupting the real queue, the MiniPlayer,
 * the media notification, and the party engines' own transition/play/seek
 * listeners (which would otherwise misread every prepare/play/pause call
 * here as a real track change), while still exercising the identical
 * default pipeline configuration PlaybackService uses — what matters for
 * output-latency parity is the pipeline's configuration, not the specific
 * instance.
 */
object ExoChirpPlayer {

    /** Must be called from a thread with a Looper; hops to Main internally so callers don't have to care. */
    suspend fun playAt(context: Context, pcm: ShortArray, sampleRate: Int, atElapsedMs: Long) {
        withContext(Dispatchers.Main) {
            val file = File(context.cacheDir, "party/calibration_${System.nanoTime()}.wav")
            try {
                file.parentFile?.mkdirs()
                writeWav(pcm, sampleRate, file)
                playFile(context, file, atElapsedMs)
            } catch (e: Exception) {
                Log.w(TAG, "Chirp playback failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    private suspend fun playFile(context: Context, file: File, atElapsedMs: Long) {
        val player = ExoPlayer.Builder(context).build()
        try {
            val ready = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> ready.complete(Unit)
                        Player.STATE_ENDED -> ended.complete(Unit)
                    }
                }
            })
            player.setMediaItem(MediaItem.fromUri(file.toUri()))
            player.prepare()
            if (withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() } == null) {
                Log.w(TAG, "Chirp player never reached STATE_READY")
                return
            }
            // Sleep the bulk of the wait (non-blocking — this runs on the
            // shared Main looper, so a long blocking sleep here would freeze
            // the whole app's UI); a short tight spin for the last stretch
            // gets us back to the sub-millisecond precision delay()'s
            // dispatch jitter alone wouldn't guarantee, same pattern used for
            // every other scheduled start in this codebase.
            val remaining = atElapsedMs - SystemClock.elapsedRealtime()
            if (remaining > SPIN_WINDOW_MS) delay(remaining - SPIN_WINDOW_MS)
            @Suppress("ControlFlowWithEmptyBody")
            while (SystemClock.elapsedRealtime() < atElapsedMs) { }
            player.play()
            Log.d(TAG, "Chirp playing at $atElapsedMs")
            // STATE_ENDED fires once the last sample is consumed by the
            // pipeline, not necessarily once the HAL has fully emitted it —
            // a small drain margin after it avoids release() cutting off the
            // tail, same reasoning as the AudioTrack version's release delay.
            withTimeoutOrNull(ENDED_TIMEOUT_MS) { ended.await() }
            delay(DRAIN_MARGIN_MS)
        } finally {
            player.release()
        }
    }

    /** Minimal 16-bit mono PCM WAV header + samples — ExoPlayer needs a real MediaItem source, not raw PCM. */
    private fun writeWav(pcm: ShortArray, sampleRate: Int, file: File) {
        val dataSize = pcm.size * 2
        file.outputStream().use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2) // byte rate = sampleRate * blockAlign (mono, 16-bit)
            header.putShort(2) // block align
            header.putShort(16) // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { body.putShort(it) }
            out.write(body.array())
        }
    }

    private const val TAG = "ExoChirpPlayer"
    private const val READY_TIMEOUT_MS = 3_000L
    private const val ENDED_TIMEOUT_MS = 3_000L
    private const val DRAIN_MARGIN_MS = 150L
    private const val SPIN_WINDOW_MS = 15L
}
