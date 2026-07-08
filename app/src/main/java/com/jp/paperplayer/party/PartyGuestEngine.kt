package com.jp.paperplayer.party

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.data.SettingsStore
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartySyncDebug
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.model.ui.SyncQuality
import com.jp.paperplayer.service.PlaybackService
import java.io.BufferedWriter
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Joins a party as a guest: discovers hosts via mDNS, connects to the control
 * socket, estimates the clock offset via ping rounds, downloads announced
 * songs into the cache, and plays them on the host's commands through its own
 * MediaController. Downloaded files are removed on leave.
 */
class PartyGuestEngine(
    private val context: Context,
    private val update: ((PartyUiState) -> PartyUiState) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd = NsdHelper(context)
    private val downloader = PartyFileDownloader(context)

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var hostAddress: String? = null
    private var httpPort: Int = 0

    private var controller: MediaController? = null
    private var preparedSongId: Long? = null
    private var preparedFile: File? = null

    // Scheduled-start bookkeeping: the party timeline the drift loop chases.
    private var pendingStartJob: Job? = null
    private var driftJob: Job? = null
    private var startPositionMs = 0L
    private var startLocalInstant = 0L

    /** hostClock - guestClock; null until the first ping round completes. */
    @Volatile
    var clockOffsetMs: Long? = null
        private set

    /**
     * Manual audio-latency compensation set by the user: positive means this
     * device's speaker output is late, so the player runs that far ahead of
     * the party timeline. The drift loop picks up changes within a tick.
     */
    @Volatile
    var latencyTrimMs: Long = SettingsStore(context).getPartyLatencyTrimMs()

    private var pingSeq = 0
    private val pendingSamples = mutableListOf<PingSample>()
    private var pendingCalibration: CompletableDeferred<PartyMessage?>? = null

    fun startDiscovery() {
        update { it.copy(isDiscovering = true, discovered = emptyList(), error = null) }
        nsd.startDiscovery(
            onFound = { party ->
                update { state ->
                    val without = state.discovered.filterNot { it.serviceName == party.serviceName }
                    state.copy(discovered = without + party)
                }
            },
            onLost = { serviceName ->
                update { state ->
                    state.copy(discovered = state.discovered.filterNot { it.serviceName == serviceName })
                }
            },
            onError = { message ->
                update { it.copy(isDiscovering = false, error = message) }
            },
        )
    }

    fun stopDiscovery() {
        nsd.stopDiscovery()
        update { it.copy(isDiscovering = false) }
    }

    fun join(party: DiscoveredParty, deviceName: String) {
        update { it.copy(isJoining = true, error = null) }
        downloader.cleanup() // purge stale files from a previous crash
        connectController()
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(party.hostAddress, party.port), CONNECT_TIMEOUT_MS)
                socket = s
                hostAddress = party.hostAddress
                writer = s.getOutputStream().bufferedWriter()
                send(PartyMessage.Hello(PartyProtocol.VERSION, deviceName))
                readLoop(s)
            } catch (e: Exception) {
                Log.d(TAG, "Join failed: ${e.message}")
                update {
                    it.copy(isJoining = false, error = "Could not join ${party.serviceName}")
                }
            }
        }
    }

    fun leave() {
        val s = socket
        val w = writer
        socket = null
        writer = null
        thread(name = "party-guest-shutdown") {
            runCatching {
                if (w != null) {
                    synchronized(w) {
                        w.write(PartyMessage.Bye.encode())
                        w.write("\n")
                        w.flush()
                    }
                }
            }
            runCatching { s?.close() }
        }
        nsd.stopDiscovery()
        releasePlayback()
        downloader.cleanup()
        scope.cancel()
        update { PartyUiState() }
    }

    // ── Control channel ─────────────────────────────────────────────────────

    private fun readLoop(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                when (val message = PartyMessage.parse(line)) {
                    is PartyMessage.Welcome -> onWelcome(message)
                    is PartyMessage.Error -> {
                        update { it.copy(isJoining = false, error = message.reason) }
                        return
                    }
                    is PartyMessage.Pong -> onPong(message)
                    is PartyMessage.Prepare -> onPrepare(message)
                    is PartyMessage.Start -> onStart(message)
                    is PartyMessage.Pause -> onPause(message)
                    is PartyMessage.Resume -> onResume(message)
                    is PartyMessage.CalibrateChirp -> pendingCalibration?.complete(message)
                    is PartyMessage.CalibrateDenied -> pendingCalibration?.complete(message)
                    is PartyMessage.Bye -> break
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connection lost: ${e.message}")
        }
        onDisconnected()
    }

    private fun onWelcome(welcome: PartyMessage.Welcome) {
        httpPort = welcome.httpPort
        stopDiscovery()
        update {
            it.copy(
                role = PartyRole.GUEST,
                partyName = welcome.partyName,
                connectedHostName = welcome.hostName,
                isJoining = false,
                discovered = emptyList(),
                error = null,
            )
        }
        scope.launch { pingRounds() }
    }

    // ── Playback ────────────────────────────────────────────────────────────

    private fun onPrepare(prepare: PartyMessage.Prepare) {
        val host = hostAddress ?: return
        pendingStartJob?.cancel()
        driftJob?.cancel()
        update {
            it.copy(
                isDownloading = true,
                nowPlaying = "${prepare.title} — ${prepare.artist}",
                startsInMs = null,
            )
        }
        scope.launch {
            try {
                val url = "http://$host:$httpPort/song/${prepare.songId}"
                val file = downloader.download(url, prepare.songId, prepare.ext, prepare.sizeBytes)
                preparedSongId = prepare.songId
                preparedFile = file
                withContext(Dispatchers.Main) {
                    val c = controller ?: return@withContext
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

    private fun onStart(start: PartyMessage.Start) {
        if (start.songId != preparedSongId) return
        schedulePlay(start.positionMs, start.atHostElapsedMs)
        update { it.copy(error = null) }
    }

    private fun onPause(pause: PartyMessage.Pause) {
        pendingStartJob?.cancel()
        driftJob?.cancel()
        update { it.copy(startsInMs = null) }
        scope.launch(Dispatchers.Main) {
            val c = controller ?: return@launch
            c.setPlaybackParameters(PlaybackParameters.DEFAULT)
            c.pause()
            c.seekTo(pause.positionMs)
        }
    }

    private fun onResume(resume: PartyMessage.Resume) {
        schedulePlay(resume.positionMs, resume.atHostElapsedMs)
    }

    /**
     * Starts playback of [positionMs] exactly when this device's clock reaches
     * the host instant [atHostElapsedMs] (converted via the measured offset).
     * If the moment already passed — slow download, late join — starts
     * immediately at the position the party has reached by now.
     */
    private fun schedulePlay(positionMs: Long, atHostElapsedMs: Long) {
        pendingStartJob?.cancel()
        driftJob?.cancel()
        pendingStartJob = scope.launch {
            val offset = clockOffsetMs ?: 0L
            val localTarget = atHostElapsedMs - offset
            // Tick the countdown down to the last ~150ms, then hand over to the
            // precise start below.
            while (true) {
                val remaining = localTarget - SystemClock.elapsedRealtime()
                if (remaining <= 150) break
                update { it.copy(startsInMs = remaining) }
                delay(minOf(remaining - 120, 100L))
            }
            update { it.copy(startsInMs = null) }
            withContext(Dispatchers.Main) {
                val c = controller ?: return@withContext
                c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                val lateBy = SystemClock.elapsedRealtime() - localTarget
                if (lateBy > 0) {
                    c.seekTo(positionMs + lateBy + SEEK_LEAD_MS + latencyTrimMs)
                    c.play()
                } else {
                    c.seekTo(positionMs + latencyTrimMs)
                    // Burn the last few milliseconds for an on-the-dot start.
                    @Suppress("ControlFlowWithEmptyBody")
                    while (SystemClock.elapsedRealtime() < localTarget) { }
                    c.play()
                }
                startPositionMs = positionMs
                startLocalInstant = localTarget
            }
            startDriftLoop()
        }
    }

    /**
     * Keeps playback locked to the party timeline: big drift is corrected with
     * a seek, small drift with an inaudible playback-speed nudge.
     */
    private fun startDriftLoop() {
        driftJob?.cancel()
        driftJob = scope.launch(Dispatchers.Main) {
            var nudging = false
            var seeks = 0
            var nudges = 0
            while (true) {
                delay(DRIFT_CHECK_INTERVAL_MS)
                val c = controller ?: break
                if (!c.isPlaying) continue
                val expected = startPositionMs + (SystemClock.elapsedRealtime() - startLocalInstant) + latencyTrimMs
                val actual = c.currentPosition
                val drift = actual - expected
                var speed = c.playbackParameters.speed
                when {
                    abs(drift) > DRIFT_SEEK_THRESHOLD_MS -> {
                        Log.d(TAG, "Drift ${drift}ms — correcting with seek")
                        c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                        nudging = false
                        seeks++
                        speed = 1f
                        c.seekTo(expected + SEEK_LEAD_MS)
                    }
                    abs(drift) > DRIFT_NUDGE_THRESHOLD_MS -> {
                        speed = if (drift > 0) NUDGE_SLOW else NUDGE_FAST
                        c.setPlaybackParameters(PlaybackParameters(speed))
                        if (!nudging) nudges++
                        nudging = true
                        Log.d(TAG, "Drift ${drift}ms — nudging at ${speed}x")
                    }
                    nudging && abs(drift) < DRIFT_SETTLED_MS -> {
                        c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                        nudging = false
                        speed = 1f
                        Log.d(TAG, "Drift settled at ${drift}ms")
                    }
                }
                val seekCount = seeks
                val nudgeCount = nudges
                val currentSpeed = speed
                update {
                    it.copy(
                        syncDebug = it.syncDebug.copy(
                            lastDriftMs = drift,
                            playbackSpeed = currentSpeed,
                            expectedPositionMs = expected,
                            actualPositionMs = actual,
                            seekCorrections = seekCount,
                            nudgeCorrections = nudgeCount,
                            driftHistory = (it.syncDebug.driftHistory + drift).takeLast(DRIFT_HISTORY_SIZE),
                        )
                    )
                }
            }
        }
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

    private fun connectController() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
            } catch (e: Exception) {
                Log.w(TAG, "Controller connection failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun releasePlayback() {
        pendingStartJob?.cancel()
        driftJob?.cancel()
        controller?.let { c ->
            ContextCompat.getMainExecutor(context).execute {
                // Only silence playback we started; leave the host's own music alone.
                if (preparedSongId != null) {
                    runCatching {
                        c.pause()
                        c.clearMediaItems()
                    }
                }
                c.release()
            }
        }
        controller = null
        preparedSongId = null
        preparedFile = null
    }

    /**
     * Runs the acoustic latency calibration: asks the host to chirp, chirps
     * back 800ms later, and measures the relative output latency from one mic
     * recording. Returns the measured trim, or a failure with a user-facing
     * reason. Caller must already hold RECORD_AUDIO.
     */
    suspend fun calibrate(): LatencyCalibrator.Result {
        val deferred = CompletableDeferred<PartyMessage?>()
        pendingCalibration = deferred
        runCatching { send(PartyMessage.CalibrateRequest) }
        val reply = withTimeoutOrNull(CALIBRATE_REPLY_TIMEOUT_MS) { deferred.await() }
        pendingCalibration = null
        return when (reply) {
            is PartyMessage.CalibrateChirp -> {
                val offset = clockOffsetMs ?: 0L
                val hostChirpLocal = reply.atHostElapsedMs - offset
                LatencyCalibrator().measure(
                    hostChirpAtLocalMs = hostChirpLocal,
                    ownChirpAtLocalMs = hostChirpLocal + OWN_CHIRP_DELAY_MS,
                )
            }
            is PartyMessage.CalibrateDenied -> LatencyCalibrator.Result.Failure(reply.reason)
            else -> LatencyCalibrator.Result.Failure("The host didn't respond — try again")
        }
    }

    // ── Clock sync ──────────────────────────────────────────────────────────

    private suspend fun pingRounds() {
        runPingRound(ClockSync.JOIN_PING_COUNT)
        while (true) {
            delay(OFFSET_REFRESH_INTERVAL_MS)
            runPingRound(ClockSync.REFRESH_PING_COUNT)
        }
    }

    private suspend fun runPingRound(count: Int) {
        synchronized(pendingSamples) { pendingSamples.clear() }
        repeat(count) {
            runCatching { send(PartyMessage.Ping(++pingSeq, SystemClock.elapsedRealtime())) }
            delay(ClockSync.PING_INTERVAL_MS)
        }
        // Grace period for the last replies to arrive.
        delay(500L)
        val samples = synchronized(pendingSamples) { pendingSamples.toList() }
        if (samples.isEmpty()) return
        clockOffsetMs = ClockSync.estimateOffsetMs(samples)
        val quality = if (ClockSync.isQualityPoor(samples)) SyncQuality.POOR else SyncQuality.GOOD
        val medianRtt = ClockSync.medianRttMs(samples)
        update {
            it.copy(
                syncQuality = quality,
                rttMs = medianRtt,
                syncDebug = it.syncDebug.copy(clockOffsetMs = clockOffsetMs, medianRttMs = medianRtt),
            )
        }
    }

    private fun onPong(pong: PartyMessage.Pong) {
        val sample = PingSample(t0 = pong.t0, t1 = pong.t1, t2 = SystemClock.elapsedRealtime())
        synchronized(pendingSamples) { pendingSamples.add(sample) }
    }

    private fun onDisconnected() {
        runCatching { socket?.close() }
        socket = null
        writer = null
        pendingStartJob?.cancel()
        driftJob?.cancel()
        scope.launch(Dispatchers.Main) { controller?.pause() }
        update {
            if (it.role == PartyRole.GUEST) {
                it.copy(error = "Lost connection to the party", isDownloading = false, startsInMs = null)
            } else {
                it.copy(isJoining = false)
            }
        }
    }

    private fun send(message: PartyMessage) {
        val w = writer ?: return
        synchronized(w) {
            w.write(message.encode())
            w.write("\n")
            w.flush()
        }
    }

    private companion object {
        const val TAG = "PartyGuestEngine"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val OFFSET_REFRESH_INTERVAL_MS = 30_000L

        /** Small lead added to corrective seeks to cover the seek latency itself. */
        const val SEEK_LEAD_MS = 60L
        const val DRIFT_CHECK_INTERVAL_MS = 500L
        const val DRIFT_SEEK_THRESHOLD_MS = 150L
        const val DRIFT_NUDGE_THRESHOLD_MS = 40L
        const val DRIFT_SETTLED_MS = 15L
        const val NUDGE_SLOW = 0.975f
        const val NUDGE_FAST = 1.025f
        const val DRIFT_HISTORY_SIZE = 60
        const val CALIBRATE_REPLY_TIMEOUT_MS = 5_000L
        const val OWN_CHIRP_DELAY_MS = 800L
    }
}
