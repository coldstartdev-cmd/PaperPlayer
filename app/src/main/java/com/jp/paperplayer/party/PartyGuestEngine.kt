package com.jp.paperplayer.party

import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
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
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    private var httpPort: Int = 0

    private val controlChannel = PartyGuestControlChannel(
        onMessage = ::dispatchMessage,
        onRejected = { reason -> update { it.copy(isJoining = false, error = reason) } },
        onJoinFailed = { partyServiceName ->
            update { it.copy(isJoining = false, error = "Could not join $partyServiceName") }
        },
        onDisconnected = ::onDisconnected,
    )

    // HOST_SYNC (in) and SYNC_STATS (out) travel over UDP, not the TCP
    // control socket — see PartyProtocol's doc comment. Persists across a
    // reconnect (same bound local port); re-learned from each WELCOME since
    // the host's UDP port could differ across host sessions.
    private val udpChannel = PartyGuestUdpChannel(onHostSync = ::onHostSync)
    private var udpLocalPort = 0

    private var controller: MediaController? = null
    private val downloadCoordinator = PartyGuestDownloadCoordinator(
        context = context,
        scope = scope,
        downloader = downloader,
        send = ::send,
        update = update,
        getController = { controller },
        hostAddress = { controlChannel.hostAddress },
        httpPort = { httpPort },
        cancelActiveCycles = {
            pendingStartJob?.cancel()
            driftJob?.cancel()
        },
        runClockSyncRound = { count -> clockSync.runPingRound(count) },
        latencyTrimMs = { latencyTrimMs },
    )

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

    private val calibration = PartyGuestCalibration(
        context = context,
        send = ::send,
        clockOffsetMs = { clockSync.clockOffsetMs },
    )

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
        lastHostSyncAtLocalMs = null
        update { it.copy(isJoining = true, error = null) }
        downloader.cleanup() // purge stale files from a previous crash
        if (!wifiLock.isHeld) wifiLock.acquire()
        connectController()
        udpLocalPort = udpChannel.start(scope)
        controlChannel.connect(party, deviceName, udpLocalPort, scope)
    }

    fun leave() {
        controlChannel.leave()
        if (wifiLock.isHeld) wifiLock.release()
        udpChannel.stop()
        nsd.stopDiscovery()
        releasePlayback()
        downloader.cleanup()
        PartyEqController.setRole(PartyEqRole.NONE)
        scope.cancel()
        update { PartyUiState() }
    }

    // ── Control channel ─────────────────────────────────────────────────────

    /** Routes every message [PartyGuestControlChannel] doesn't special-case itself (BYE, ERROR — see its doc comment). */
    private fun dispatchMessage(message: PartyMessage) {
        when (message) {
            is PartyMessage.Welcome -> onWelcome(message)
            is PartyMessage.Pong -> clockSync.onPong(message)
            is PartyMessage.Prepare -> downloadCoordinator.onPrepare(message)
            is PartyMessage.SyncCheck -> downloadCoordinator.onSyncCheck(message)
            is PartyMessage.Start -> onStart(message)
            is PartyMessage.Pause -> onPause(message)
            is PartyMessage.Resume -> onResume(message)
            is PartyMessage.CalibrateChirp -> calibration.onReply(message)
            is PartyMessage.CalibrateDenied -> calibration.onReply(message)
            is PartyMessage.SetEqRole -> PartyEqController.setRole(message.role)
            else -> {}
        }
    }

    private fun onWelcome(welcome: PartyMessage.Welcome) {
        httpPort = welcome.httpPort
        controlChannel.resetReconnectAttempts()
        controlChannel.hostAddress?.let { udpChannel.setHostEndpoint(InetSocketAddress(InetAddress.getByName(it), welcome.udpPort)) }
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

    private fun onStart(start: PartyMessage.Start) {
        if (start.songId != downloadCoordinator.preparedSongId) return
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
                if (downloadCoordinator.preparedSongId != null) {
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
        downloadCoordinator.clearPrepared()
    }

    /**
     * Runs the acoustic latency calibration; delegates to [PartyGuestCalibration]
     * so this stays a stable one-line entry point for [PartyManager.calibrate].
     * Caller must already hold RECORD_AUDIO.
     */
    suspend fun calibrate(onProgress: (String) -> Unit = {}): LatencyCalibrator.Result =
        calibration.calibrate(onProgress)

    /** The host's live position, relayed every drift tick; the drift loop chases this directly. */
    private fun onHostSync(sync: PartyMessage.HostSync) {
        if (sync.atHostElapsedMs <= lastHostSyncHostElapsedMs) return
        val offset = clockSync.clockOffsetMs ?: return
        lastHostSyncHostElapsedMs = sync.atHostElapsedMs
        lastHostSyncAtLocalMs = sync.atHostElapsedMs - offset
        lastHostSyncPositionMs = sync.positionMs
    }

    /**
     * [willRetry] reflects whether [PartyGuestControlChannel] has already
     * scheduled a reconnect attempt — this only handles the parts that
     * aren't the channel's concern: playback-core job cancellation, pausing
     * the player, and the UI-facing error text (worded differently for
     * "still trying" vs "gave up").
     */
    private fun onDisconnected(willRetry: Boolean) {
        pendingStartJob?.cancel()
        driftJob?.cancel()
        scope.launch(Dispatchers.Main) { controller?.pause() }
        update {
            when {
                it.role == PartyRole.GUEST && willRetry ->
                    it.copy(error = "Connection lost — reconnecting…", isDownloading = false, startsInMs = null)
                it.role == PartyRole.GUEST ->
                    it.copy(error = "Lost connection to the party", isDownloading = false, startsInMs = null)
                willRetry -> it
                else -> it.copy(isJoining = false)
            }
        }
    }

    private fun send(message: PartyMessage) {
        controlChannel.send(message)
    }

    private companion object {
        const val TAG = "PartyGuestEngine"

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

        /** Player-error recovery: retries within this window count toward the cap below. */
        const val ERROR_WINDOW_MS = 10_000L
        const val MAX_ERROR_RECOVERIES = 3
    }
}
