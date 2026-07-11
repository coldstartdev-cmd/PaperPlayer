package com.jp.paperplayer.party

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import com.jp.paperplayer.model.ui.PartyUiState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles the two host -> guest messages that touch the downloaded file:
 * PREPARE (download + stage the player) and SYNC_CHECK (the pre-start
 * checklist's file-integrity half). Kept together rather than split further
 * since [onSyncCheck]'s file check reads exactly what [onPrepare] writes
 * ([preparedSongId]/preparedFile/preparedSha256) — splitting them would only
 * expose that state across another class boundary for no benefit.
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
) {
    /** Read by [PartyGuestEngine.onStart] (must match) and releasePlayback (must clean up only what we started). */
    @Volatile
    var preparedSongId: Long? = null
        private set
    private var preparedFile: File? = null
    private var preparedSha256: String? = null

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
            try {
                val url = "http://$host:${httpPort()}/song/${prepare.songId}"
                val file = downloader.download(url, prepare.songId, prepare.ext, prepare.sizeBytes, prepare.sha256)
                preparedSongId = prepare.songId
                preparedFile = file
                preparedSha256 = prepare.sha256
                withContext(Dispatchers.Main) {
                    val c = getController() ?: return@withContext
                    c.pause()
                    c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                    c.setMediaItem(buildPartyMediaItem(prepare, file))
                    c.prepare()
                }
                update { it.copy(isDownloading = false) }
                send(PartyMessage.Ready(prepare.songId))
            } catch (e: Exception) {
                Log.w(TAG, "Prepare failed for ${prepare.songId}: ${e.message}")
                update { it.copy(isDownloading = false, error = "Could not download ${prepare.title}") }
            }
        }
    }

    /**
     * Pre-start checklist, run every time the host is about to start playback:
     * verify the prepared file still matches the host's hash, re-measure the
     * clock offset with a fresh ping round, park the player at the start
     * position, then ack. A failed file check makes the host re-send the file.
     */
    fun onSyncCheck(check: PartyMessage.SyncCheck) {
        cancelActiveCycles()
        update { it.copy(startsInMs = null) }
        scope.launch {
            val fileOk = check.songId == preparedSongId &&
                preparedFile?.exists() == true &&
                (check.sha256.isEmpty() || check.sha256 == preparedSha256)
            if (!fileOk) {
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
    }

    private fun buildPartyMediaItem(prepare: PartyMessage.Prepare, file: File): MediaItem =
        MediaItem.Builder()
            .setUri(file.toUri())
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
    }
}
