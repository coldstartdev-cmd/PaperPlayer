package com.jp.paperplayer.party

import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.data.SettingsStore
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartySyncDebug
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.model.ui.SyncQuality
import com.jp.paperplayer.service.PlaybackService
import java.io.BufferedWriter
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
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

    // Keeps WiFi awake while in a party; screen-off power save otherwise
    // drops the control socket mid-song.
    @Suppress("DEPRECATION")
    private val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PaperPlayer:partyGuest")

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var hostAddress: String? = null
    private var httpPort: Int = 0

    // HOST_SYNC (in) and SYNC_STATS (out) travel over UDP, not the TCP
    // control socket — see PartyProtocol's doc comment. Persists across a
    // reconnect (same bound local port); re-learned from each WELCOME since
    // the host's UDP port could differ across host sessions.
    private val udpChannel = PartyGuestUdpChannel(onHostSync = ::onHostSync)
    private var udpLocalPort = 0

    // Auto-reconnect after a dropped connection (WiFi power-save, AP roaming).
    @Volatile
    private var leaving = false
    private var lastParty: DiscoveredParty? = null
    private var lastDeviceName: String = ""
    private var reconnectAttempts = 0

    private var controller: MediaController? = null
    private var preparedSongId: Long? = null
    private var preparedFile: File? = null
    private var preparedSha256: String? = null

    // Scheduled-start bookkeeping: the party timeline the drift loop chases.
    private var pendingStartJob: Job? = null
    private var driftJob: Job? = null
    private var startPositionMs = 0L
    private var startLocalInstant = 0L

    // The host's own live position, relayed via HOST_SYNC — the drift loop
    // chases this directly (the host is the reference) instead of the
    // idealized start-position + elapsed-time formula above, which is only a
    // fallback until the first sample arrives after a scheduled start.
    @Volatile
    private var lastHostSyncAtLocalMs: Long? = null
    @Volatile
    private var lastHostSyncPositionMs = 0L

    // The host's elapsedRealtime at the last HOST_SYNC actually applied —
    // compared against each new packet's own timestamp so a reordered or
    // duplicate UDP datagram (each tick is sent twice, and UDP doesn't
    // guarantee order) can't overwrite a newer sample with an older one.
    // Reset alongside lastHostSyncAtLocalMs in schedulePlay for the same
    // "belongs to a previous epoch" reason.
    @Volatile
    private var lastHostSyncHostElapsedMs = Long.MIN_VALUE

    // ExoPlayer stops for good on a fatal error (decoder hiccup, transient IO
    // glitch reading the downloaded file) and never retries on its own; the
    // drift loop keeps ticking through it but never nudges a dead player back
    // to life, so playback silently goes quiet until the next song or a manual
    // pause/play. Recover automatically, but stop retrying if it keeps failing.
    private var errorRecoveryCount = 0
    private var errorRecoveryWindowStart = 0L

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Player error: ${error.message}", error)
            recoverFromPlayerError()
        }
    }

    private val settings = SettingsStore(context)

    private val clockSync = PartyGuestClockSync(send = ::send, update = update)

    /**
     * Manual audio-latency compensation set by the user: positive means this
     * device's speaker output is late, so the player runs that far ahead of
     * the party timeline. The drift loop picks up changes within a tick.
     */
    @Volatile
    var latencyTrimMs: Long = settings.getPartyLatencyTrimMs()

    /**
     * Where the party should be right now: extrapolated from the host's last
     * reported real position (HOST_SYNC) when one has arrived, since the host
     * is the reference every device matches. Falls back to the idealized
     * start-position + elapsed-time formula for the brief window right after
     * a scheduled start, before the first host sample lands.
     */
    private fun expectedPositionMs(nowLocal: Long): Long {
        val hostAtLocal = lastHostSyncAtLocalMs
        return if (hostAtLocal != null) {
            lastHostSyncPositionMs + (nowLocal - hostAtLocal) + latencyTrimMs
        } else {
            startPositionMs + (nowLocal - startLocalInstant) + latencyTrimMs
        }
    }

    // Written on the caller's thread (calibrate() hops onto its own IO
    // context, but the readLoop that delivers the reply is a separate IO
    // thread) so this needs @Volatile for cross-thread visibility.
    @Volatile
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
        leaving = false
        reconnectAttempts = 0
        lastParty = party
        lastDeviceName = deviceName
        lastHostSyncAtLocalMs = null
        update { it.copy(isJoining = true, error = null) }
        downloader.cleanup() // purge stale files from a previous crash
        if (!wifiLock.isHeld) wifiLock.acquire()
        connectController()
        udpLocalPort = udpChannel.start(scope)
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(party.hostAddress, party.port), CONNECT_TIMEOUT_MS)
                socket = s
                hostAddress = party.hostAddress
                writer = s.getOutputStream().bufferedWriter()
                send(PartyMessage.Hello(PartyProtocol.VERSION, deviceName, udpLocalPort))
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
        leaving = true
        if (wifiLock.isHeld) wifiLock.release()
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
        udpChannel.stop()
        nsd.stopDiscovery()
        releasePlayback()
        downloader.cleanup()
        PartyEqController.setRole(PartyEqRole.NONE)
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
                    is PartyMessage.Pong -> clockSync.onPong(message)
                    is PartyMessage.Prepare -> onPrepare(message)
                    is PartyMessage.SyncCheck -> onSyncCheck(message)
                    is PartyMessage.Start -> onStart(message)
                    is PartyMessage.Pause -> onPause(message)
                    is PartyMessage.Resume -> onResume(message)
                    is PartyMessage.CalibrateChirp -> pendingCalibration?.complete(message)
                    is PartyMessage.CalibrateDenied -> pendingCalibration?.complete(message)
                    is PartyMessage.SetEqRole -> PartyEqController.setRole(message.role)
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
        reconnectAttempts = 0
        hostAddress?.let { udpChannel.setHostEndpoint(InetSocketAddress(InetAddress.getByName(it), welcome.udpPort)) }
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
        clockSync.start(scope)
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
                val file = downloader.download(url, prepare.songId, prepare.ext, prepare.sizeBytes, prepare.sha256)
                preparedSongId = prepare.songId
                preparedFile = file
                preparedSha256 = prepare.sha256
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

    /**
     * Pre-start checklist, run every time the host is about to start playback:
     * verify the prepared file still matches the host's hash, re-measure the
     * clock offset with a fresh ping round, park the player at the start
     * position, then ack. A failed file check makes the host re-send the file.
     */
    private fun onSyncCheck(check: PartyMessage.SyncCheck) {
        pendingStartJob?.cancel()
        driftJob?.cancel()
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
            clockSync.runPingRound(ClockSync.REFRESH_PING_COUNT)
            withContext(Dispatchers.Main) {
                controller?.let { c ->
                    c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                    c.pause()
                    c.seekTo(check.positionMs + latencyTrimMs)
                }
            }
            runCatching { send(PartyMessage.SyncReady(check.songId, true, "")) }
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
        errorRecoveryCount = 0
        // Discard any HOST_SYNC sample from before this start — it belongs to
        // whatever the party was doing previously and would extrapolate to a
        // bogus "expected" position until a fresh one lands.
        lastHostSyncAtLocalMs = null
        lastHostSyncHostElapsedMs = Long.MIN_VALUE
        pendingStartJob = scope.launch {
            val offset = clockSync.awaitOffset()
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
            var lastAdjustmentAtMs = 0L
            while (true) {
                delay(DRIFT_CHECK_INTERVAL_MS)
                val c = controller ?: break
                if (!c.isPlaying) continue
                // An unexpected exception from a single tick (a MediaController
                // call landing mid-teardown, a transient state inconsistency)
                // used to kill this whole coroutine silently — sync for the
                // rest of the song, with no retry and no visible error. One
                // bad tick shouldn't cost the rest of the song; log it and
                // pick back up next tick instead. CancellationException must
                // still propagate, or driftJob.cancel() (called from onPrepare,
                // onSyncCheck, onPause, schedulePlay, releasePlayback) would
                // stop actually stopping this loop.
                try {
                    val nowLocal = SystemClock.elapsedRealtime()
                    val expected = expectedPositionMs(nowLocal)
                    val actual = c.currentPosition
                    val drift = actual - expected
                    var speed = c.playbackParameters.speed
                    // Only large drift (a real, audible problem) gets corrected on
                    // sight. Nudge/settle changes are throttled to at most once
                    // per ADJUSTMENT_COOLDOWN_MS: reacting to every single 500ms
                    // sample meant we'd often change speed again before the
                    // previous change had time to actually pull drift back in,
                    // producing constant nudge<->settle flapping (and the
                    // corresponding IN_SYNC/DRIFTING flicker on the dashboard)
                    // instead of one adjustment settling before the next.
                    val onCooldown = nowLocal - lastAdjustmentAtMs < ADJUSTMENT_COOLDOWN_MS
                    // ExoPlayer's Sonic time-stretch processor has a known
                    // crash (ArrayIndexOutOfBoundsException in
                    // Sonic.insertPitchPeriod, from queueEndOfStream) when
                    // end-of-stream is reached while speed != 1x. Flattening
                    // speed for the last stretch of the track means the
                    // stream can never actually end mid-nudge — a couple of
                    // seconds of unmanaged drift right as the song finishes
                    // is inaudible anyway, and far cheaper than the crash's
                    // recovery (a hard re-seek that itself disrupts sync).
                    val durationMs = c.duration
                    val remainingMs = if (durationMs == C.TIME_UNSET) Long.MAX_VALUE else durationMs - actual
                    when {
                        remainingMs < END_OF_TRACK_SETTLE_MS -> {
                            setPlaybackSpeed(c, 1f)
                            nudging = false
                            speed = 1f
                        }
                        abs(drift) > DRIFT_SEEK_THRESHOLD_MS -> {
                            Log.d(TAG, "Drift ${drift}ms — correcting with seek")
                            setPlaybackSpeed(c, 1f)
                            nudging = false
                            seeks++
                            speed = 1f
                            c.seekTo(expected + SEEK_LEAD_MS)
                            lastAdjustmentAtMs = nowLocal
                        }
                        onCooldown -> {
                            // Hold the last adjustment; let it take effect before
                            // reacting to a fresher sample.
                        }
                        abs(drift) > DRIFT_NUDGE_THRESHOLD_MS -> {
                            // Gentle nudge inside the coarse band so the frequent
                            // small corrections stay inaudible.
                            val gentle = abs(drift) <= DRIFT_COARSE_NUDGE_MS
                            speed = when {
                                drift > 0 -> if (gentle) NUDGE_SLOW_FINE else NUDGE_SLOW
                                else -> if (gentle) NUDGE_FAST_FINE else NUDGE_FAST
                            }
                            setPlaybackSpeed(c, speed)
                            if (!nudging) nudges++
                            nudging = true
                            lastAdjustmentAtMs = nowLocal
                            Log.d(TAG, "Drift ${drift}ms — nudging at ${speed}x")
                        }
                        nudging && abs(drift) < DRIFT_SETTLED_MS -> {
                            setPlaybackSpeed(c, 1f)
                            nudging = false
                            speed = 1f
                            lastAdjustmentAtMs = nowLocal
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
                    // Raw telemetry for the host dashboard — the same numbers
                    // driving this device's own debug panel, not just the
                    // collapsed drift, so the host can see exactly what this
                    // guest observed rather than trusting one derived figure.
                    // hostAtMs lets the host place every guest's sample on its
                    // own timeline regardless of delivery jitter — including
                    // WiFi hiccups: a gap between consecutive hostAtMs values
                    // that's much larger than DRIFT_CHECK_INTERVAL_MS is a direct
                    // sign of exactly that, distinct from genuine audio drift.
                    val stats = PartyMessage.SyncStats(
                        deviceAtMs = nowLocal,
                        hostAtMs = nowLocal + (clockSync.clockOffsetMs ?: 0L),
                        expectedPositionMs = expected,
                        actualPositionMs = actual,
                        rttMs = clockSync.lastMedianRttMs,
                        playbackSpeed = currentSpeed,
                        seekCorrections = seekCount,
                        nudgeCorrections = nudgeCount,
                        latencyTrimMs = latencyTrimMs,
                    )
                    udpChannel.sendAsync(stats, scope)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Drift tick failed, retrying next tick: ${e.message}")
                }
            }
        }
    }

    /**
     * Only calls setPlaybackParameters when the speed is actually changing.
     * The nudge loop recomputes its target speed every tick even when it
     * hasn't moved — there are only a handful of fixed nudge values, not a
     * continuous function of drift, so the same value routinely gets
     * recomputed for many consecutive ticks. Reapplying it anyway, every
     * 500ms for minutes at a stretch, turned out to destabilize ExoPlayer's
     * Sonic time-stretch processor: real observed crashes
     * (ArrayIndexOutOfBoundsException in Sonic.insertPitchPeriod) followed
     * long runs of "nudging at Nx" log lines with an unchanged N. Sonic
     * isn't built to be reconfigured with the same parameters indefinitely.
     */
    private fun setPlaybackSpeed(controller: MediaController, speed: Float) {
        if (controller.playbackParameters.speed != speed) {
            controller.setPlaybackParameters(PlaybackParameters(speed))
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
                controller = future.get().also { it.addListener(playerListener) }
            } catch (e: Exception) {
                Log.w(TAG, "Controller connection failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Main thread only. Re-prepares and re-seeks to where the party timeline
     * should be right now, so a transient player error clears itself instead
     * of leaving this device silent until the next song. Gives up after a
     * burst of repeated errors rather than spinning forever on a broken file.
     */
    private fun recoverFromPlayerError() {
        val c = controller ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - errorRecoveryWindowStart > ERROR_WINDOW_MS) {
            errorRecoveryWindowStart = now
            errorRecoveryCount = 0
        }
        errorRecoveryCount++
        if (errorRecoveryCount > MAX_ERROR_RECOVERIES) {
            Log.e(TAG, "Giving up after repeated player errors")
            update { it.copy(error = "Playback error — waiting for the next song") }
            return
        }
        // Reset speed before re-preparing: the crash this most often recovers
        // from (Sonic's queueEndOfStream ArrayIndexOutOfBoundsException) is
        // itself triggered by ending a stream while speed != 1x, and the
        // freshly re-prepared pipeline inherits whatever PlaybackParameters
        // were last set. Recovering into the same non-1x state risked
        // immediately re-triggering the same crash on the next tick.
        c.setPlaybackParameters(PlaybackParameters.DEFAULT)
        c.prepare()
        if (driftJob?.isActive == true) {
            c.seekTo(expectedPositionMs(now) + SEEK_LEAD_MS)
        }
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
                c.removeListener(playerListener)
                c.release()
            }
        }
        controller = null
        preparedSongId = null
        preparedFile = null
        preparedSha256 = null
    }

    /**
     * Runs the acoustic latency calibration once per [Chirp.BANDS] frequency
     * band and takes the median of the successful measurements. A single
     * band's chirp pair can get thrown off by a speaker/mic resonance or
     * ambient noise sitting right in that band; sweeping several bands and
     * combining them makes the result robust to any one bad measurement.
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
     * Called from [calibrate], which already runs on this engine's IO scope,
     * unlike everything else here which is dispatched onto it explicitly —
     * so this can touch the socket directly. Blocking socket I/O on Main
     * throws NetworkOnMainThreadException, which was getting silently
     * swallowed by the runCatching around send(), meaning the request never
     * actually left the device.
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
                val offset = clockSync.clockOffsetMs ?: 0L
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

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** The host's live position, relayed every drift tick; the drift loop chases this directly. */
    private fun onHostSync(sync: PartyMessage.HostSync) {
        if (sync.atHostElapsedMs <= lastHostSyncHostElapsedMs) return
        val offset = clockSync.clockOffsetMs ?: return
        lastHostSyncHostElapsedMs = sync.atHostElapsedMs
        lastHostSyncAtLocalMs = sync.atHostElapsedMs - offset
        lastHostSyncPositionMs = sync.positionMs
    }

    private fun onDisconnected() {
        runCatching { socket?.close() }
        socket = null
        writer = null
        pendingStartJob?.cancel()
        driftJob?.cancel()
        scope.launch(Dispatchers.Main) { controller?.pause() }
        val party = lastParty
        if (!leaving && party != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            update {
                if (it.role == PartyRole.GUEST) {
                    it.copy(error = "Connection lost — reconnecting…", isDownloading = false, startsInMs = null)
                } else it
            }
            attemptReconnect(party)
            return
        }
        update {
            if (it.role == PartyRole.GUEST) {
                it.copy(error = "Lost connection to the party", isDownloading = false, startsInMs = null)
            } else {
                it.copy(isJoining = false)
            }
        }
    }

    /**
     * Rejoins the last host after a dropped connection. The host treats us as
     * a fresh late joiner: WELCOME, then the current song's PREPARE — the
     * cached download passes the hash check, so READY is near-instant and the
     * host's catch-up START drops us back into the running song in sync.
     */
    private fun attemptReconnect(party: DiscoveredParty) {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (leaving) return@launch
            Log.d(TAG, "Reconnect attempt $reconnectAttempts to ${party.hostAddress}")
            try {
                val s = Socket()
                s.connect(InetSocketAddress(party.hostAddress, party.port), CONNECT_TIMEOUT_MS)
                socket = s
                hostAddress = party.hostAddress
                writer = s.getOutputStream().bufferedWriter()
                send(PartyMessage.Hello(PartyProtocol.VERSION, lastDeviceName, udpLocalPort))
                readLoop(s)
            } catch (e: Exception) {
                Log.d(TAG, "Reconnect failed: ${e.message}")
                onDisconnected() // schedules the next attempt or gives up
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

        /** Small lead added to corrective seeks to cover the seek latency itself. */
        const val SEEK_LEAD_MS = 60L
        // Band tuning from listening tests: under ~±10ms devices fuse into one
        // sound; 15–40ms is audible flam, so the loop must not idle there.
        const val DRIFT_CHECK_INTERVAL_MS = 500L
        // Nudge/settle changes are throttled to this cadence so each
        // adjustment has time to land before the next one is judged; large
        // (seek-worthy) drift always corrects immediately regardless.
        const val ADJUSTMENT_COOLDOWN_MS = 1_500L
        const val DRIFT_SEEK_THRESHOLD_MS = 150L
        const val DRIFT_NUDGE_THRESHOLD_MS = 10L
        const val DRIFT_COARSE_NUDGE_MS = 40L
        const val DRIFT_SETTLED_MS = 4L
        const val NUDGE_SLOW = 0.975f
        const val NUDGE_FAST = 1.025f
        const val NUDGE_SLOW_FINE = 0.99f
        const val NUDGE_FAST_FINE = 1.01f
        const val DRIFT_HISTORY_SIZE = 60

        /** Speed is pinned to 1x once this close to the track's natural end — see the Sonic crash comment above. */
        const val END_OF_TRACK_SETTLE_MS = 2_000L

        /** Reconnect every few seconds for ~2 minutes before giving up. */
        const val RECONNECT_DELAY_MS = 3_000L
        const val MAX_RECONNECT_ATTEMPTS = 40
        const val CALIBRATE_REPLY_TIMEOUT_MS = 5_000L

        /** Player-error recovery: retries within this window count toward the cap below. */
        const val ERROR_WINDOW_MS = 10_000L
        const val MAX_ERROR_RECOVERIES = 3
    }
}
