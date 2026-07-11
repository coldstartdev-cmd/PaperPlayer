package com.jp.paperplayer.party

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.jp.paperplayer.data.PartyFileTransferMode
import com.jp.paperplayer.model.ui.PartyUiState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles the two host -> guest messages that touch getting a song's audio
 * onto this device: PREPARE (either download the file, or point the player
 * straight at the host's HTTP URL, per [fileTransferMode]) and SYNC_CHECK
 * (the pre-start checklist's integrity half). Kept together rather than
 * split further since [onSyncCheck]'s check reads exactly what [onPrepare]
 * writes ([preparedSongId]/preparedFile/preparedSha256/isStreaming) —
 * splitting them would only expose that state across another class boundary
 * for no benefit.
 */
class PartyGuestDownloadCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloader: PartyFileDownloader,
    private val send: (PartyMessage) -> Unit,
    private val update: ((PartyUiState) -> PartyUiState) -> Unit,
    private val getController: () -> MediaController?,
    private val hostAddress: () -> String?,
    private val httpPort: () -> Int,
    private val cancelActiveCycles: () -> Unit,
    private val runClockSyncRound: suspend (count: Int) -> Unit,
    private val latencyTrimMs: () -> Long,
    private val fileTransferMode: () -> PartyFileTransferMode,
) {
    /** Read by [PartyGuestEngine.onStart] (must match) and releasePlayback (must clean up only what we started). */
    @Volatile
    var preparedSongId: Long? = null
        private set
    private var preparedFile: File? = null
    private var preparedSha256: String? = null

    /** True when [preparedSongId] is playing straight from the host's HTTP URL instead of a downloaded local file. */
    @Volatile
    private var isStreaming = false

    fun onPrepare(prepare: PartyMessage.Prepare) {
        val host = hostAddress() ?: return
        cancelActiveCycles()
        update {
            it.copy(
                isDownloading = true,
                nowPlaying = "${prepare.title} — ${prepare.artist}",
                startsInMs = null,
            )
        }
        scope.launch {
            if (fileTransferMode() == PartyFileTransferMode.STREAM) {
                prepareStreaming(prepare, host)
            } else {
                prepareDownload(prepare, host)
            }
        }
    }

    private suspend fun prepareDownload(prepare: PartyMessage.Prepare, host: String) {
        try {
            val url = "http://$host:${httpPort()}/song/${prepare.songId}"
            val file = downloader.download(url, prepare.songId, prepare.ext, prepare.sizeBytes, prepare.sha256)
            preparedSongId = prepare.songId
            preparedFile = file
            preparedSha256 = prepare.sha256
            isStreaming = false
            withContext(Dispatchers.Main) {
                val c = getController() ?: return@withContext
                c.pause()
                c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                c.setMediaItem(buildMediaItem(prepare, file.toUri()))
                c.prepare()
            }
            update { it.copy(isDownloading = false) }
            send(PartyMessage.Ready(prepare.songId))
        } catch (e: Exception) {
            Log.w(TAG, "Prepare failed for ${prepare.songId}: ${e.message}")
            update { it.copy(isDownloading = false, error = "Could not download ${prepare.title}") }
        }
    }

    /**
     * No local file — the player reads directly from the host's HTTP URL
     * (Range-request-capable, see [PartyFileServer]). Unlike a downloaded
     * file, whose `prepare()` is a near-instant local-disk operation, this
     * is network-dependent, so — unlike [prepareDownload] — this waits for
     * [Player.STATE_READY] before declaring READY, via [awaitPlayerReady].
     */
    private suspend fun prepareStreaming(prepare: PartyMessage.Prepare, host: String) {
        try {
            val url = "http://$host:${httpPort()}/song/${prepare.songId}"
            preparedSongId = prepare.songId
            preparedFile = null
            preparedSha256 = null
            isStreaming = true
            val ready = withContext(Dispatchers.Main) {
                val c = getController() ?: return@withContext false
                c.pause()
                c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                c.setMediaItem(buildMediaItem(prepare, url.toUri()))
                c.prepare()
                awaitPlayerReady(c)
            }
            update { it.copy(isDownloading = false) }
            if (ready) {
                send(PartyMessage.Ready(prepare.songId))
            } else {
                Log.w(TAG, "Stream never became ready for ${prepare.songId}")
                update { it.copy(error = "Could not stream ${prepare.title}") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream prepare failed for ${prepare.songId}: ${e.message}")
            update { it.copy(isDownloading = false, error = "Could not stream ${prepare.title}") }
        }
    }

    /**
     * Main thread only. Waits for [controller] to reach [Player.STATE_READY]
     * after a `prepare()` call, or gives up — bounded by
     * [STREAM_READY_TIMEOUT_MS] (comfortably under the host's own
     * `READY_TIMEOUT_MS`, so this guest can fail gracefully before the host
     * gives up waiting on it) and by an [Player.Listener.onPlayerError]
     * fail-fast, since a broken stream goes to `STATE_IDLE` + an error, not
     * `STATE_READY` — waiting on state alone would hang forever. The
     * listener is temporary but attached to the long-lived shared
     * [MediaController] (unlike a throwaway player), so it's removed in a
     * `finally` on every exit path to avoid leaking a stale one-shot
     * listener that could fire against a later, unrelated prepare.
     */
    private suspend fun awaitPlayerReady(controller: MediaController): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ready.complete(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                ready.complete(false)
            }
        }
        controller.addListener(listener)
        return try {
            if (controller.playbackState == Player.STATE_READY) {
                true
            } else {
                withTimeoutOrNull(STREAM_READY_TIMEOUT_MS) { ready.await() } ?: false
            }
        } finally {
            controller.removeListener(listener)
        }
    }

    /**
     * Pre-start checklist, run every time the host is about to start playback:
     * verify the guest still has the right thing prepared, re-measure the
     * clock offset with a fresh ping round, park the player at the start
     * position, then ack. A failed check makes the host re-send the file (or,
     * for a streaming guest, re-run PREPARE to rebuild the media item).
     */
    fun onSyncCheck(check: PartyMessage.SyncCheck) {
        cancelActiveCycles()
        update { it.copy(startsInMs = null) }
        scope.launch {
            val ok = if (isStreaming) {
                // No local file to hash — trust the live stream, but still
                // require the player not to already be sitting on an error,
                // so a genuinely broken stream still triggers the host's
                // resend-and-retry path instead of reporting ok=true and
                // seeking a dead player.
                check.songId == preparedSongId && getController()?.playerError == null
            } else {
                check.songId == preparedSongId &&
                    preparedFile?.exists() == true &&
                    (check.sha256.isEmpty() || check.sha256 == preparedSha256)
            }
            if (!ok) {
                Log.w(TAG, "Sync check failed for ${check.songId} — asking for the file again")
                runCatching { send(PartyMessage.SyncReady(check.songId, false, "file missing or stale")) }
                return@launch
            }
            runClockSyncRound(ClockSync.REFRESH_PING_COUNT)
            withContext(Dispatchers.Main) {
                getController()?.let { c ->
                    c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                    c.pause()
                    c.seekTo(check.positionMs + latencyTrimMs())
                }
            }
            runCatching { send(PartyMessage.SyncReady(check.songId, true, "")) }
        }
    }

    /** Called from releasePlayback (leave/reconnect teardown). */
    fun clearPrepared() {
        preparedSongId = null
        preparedFile = null
        preparedSha256 = null
        isStreaming = false
    }

    private fun buildMediaItem(prepare: PartyMessage.Prepare, uri: Uri): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            // Non-numeric media id: keeps guest play counts clean and the
            // MiniPlayer hidden (it resolves songs via mediaId.toLongOrNull()).
            .setMediaId("party:${prepare.songId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(prepare.title)
                    .setArtist(prepare.artist)
                    .setAlbumTitle(prepare.album)
                    .build()
            )
            .build()

    private companion object {
        const val TAG = "PartyGuestDownloadCoordinator"

        /**
         * Ceiling for a streaming source to reach STATE_READY after
         * prepare() — comfortably under the host's own READY_TIMEOUT_MS
         * (15s) so this guest fails gracefully before the host gives up
         * waiting on it.
         */
        const val STREAM_READY_TIMEOUT_MS = 12_000L
    }
}
