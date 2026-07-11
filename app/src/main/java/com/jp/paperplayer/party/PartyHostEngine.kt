package com.jp.paperplayer.party

import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.data.PartyMember
import com.jp.paperplayer.model.data.PartyMemberStatus
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.service.PlaybackService
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Runs a party as the host. Advertises via mDNS, accepts guest control
 * connections, serves song bytes over HTTP, and observes the host's normal
 * playback through its own MediaController: every track change pauses
 * playback and ships the file to all guests (PREPARE -> READY). Every audible
 * start — new track, resume, or seek — then runs the same pre-start checklist
 * (SYNC_CHECK -> SYNC_READY: guests re-verify the file hash, re-measure the
 * clock offset, and pre-seek) before the scheduled START. Pauses propagate
 * immediately; silence needs no synchronization.
 */
class PartyHostEngine(
    private val context: Context,
    private val update: ((PartyUiState) -> PartyUiState) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd = NsdHelper(context)

    // Keeps WiFi awake while hosting; screen-off power save otherwise stalls
    // guest connections and file transfers.
    @Suppress("DEPRECATION")
    private val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PaperPlayer:partyHost")
    private val songCatalog = PartyHostSongCatalog(context)
    private val fileServer = PartyFileServer(context) { songId ->
        runBlocking { songCatalog.resolve(songId) }
    }

    private var serverSocket: ServerSocket? = null
    private var udpLocalPort = 0
    private val udpChannel = PartyHostUdpChannel { memberId, stats, receivedAtHostMs ->
        val client = synchronized(clients) { clients[memberId] } ?: return@PartyHostUdpChannel
        onSyncStats(client, stats, receivedAtHostMs)
    }
    private val clients = mutableMapOf<String, ClientConnection>()
    private val rosterStatus = PartyHostRosterStatus()
    private val syncStatsTracker = PartyHostSyncStatsTracker(
        timeline = object : HostTimelineSnapshot {
            override val preparing: Boolean get() = this@PartyHostEngine.preparing
            override val hostDriftActive: Boolean get() = hostDriftJob?.isActive == true
            override val lastStartAtHostMs: Long get() = this@PartyHostEngine.lastStartAtHostMs
            override val lastKnownHostPositionMs: Long get() = this@PartyHostEngine.lastKnownHostPositionMs
            override val lastKnownHostSampleAtMs: Long get() = this@PartyHostEngine.lastKnownHostSampleAtMs
        },
    )
    private var hostDeviceName: String = ""
    private var partyName: String = ""

    // Playback take-over. Controller calls stay on the main thread; socket
    // writes hop to the IO scope.
    private var controller: MediaController? = null
    private var pendingEngineEvents = 0
    private var pendingEngineSeeks = 0
    private var startCycle: Job? = null

    // These are written on Main (player listener callbacks, the start-cycle
    // and drift-loop coroutines dispatched onto it) but read from
    // independent IO-dispatched coroutines with no causal link to that
    // write — the UDP receive loop and per-client TCP handler threads call
    // into onSyncStats/maybeResync/onGuestReady on their own schedule, not
    // as a continuation of whatever last touched these fields. A plain var
    // gives no visibility guarantee across that gap; @Volatile does.
    @Volatile
    private var preparing = false
    @Volatile
    private var hostDriftJob: Job? = null

    // Where the announced timeline says playback should be right now — set at
    // the top of every drift loop start, used to re-seek after a player error.
    @Volatile
    private var lastStartPositionMs = 0L
    @Volatile
    private var lastStartAtHostMs = 0L

    // The host's own most recent real position sample, refreshed every
    // drift tick (~500ms) — used to verify guest drift against, instead of
    // extrapolating from lastStartAtHostMs (the last scheduled start, which
    // can be minutes ago): the host's system clock and its audio hardware
    // clock are different physical clocks, and even a small skew between
    // them accumulates into a real gap over minutes of extrapolation. A
    // sub-second-old anchor never gives skew room to accumulate.
    @Volatile
    private var lastKnownHostPositionMs = 0L
    @Volatile
    private var lastKnownHostSampleAtMs = 0L

    // ExoPlayer stops for good on a fatal error (decoder hiccup, transient IO
    // glitch reading the file) and never retries on its own; recover
    // automatically, but stop retrying if it keeps failing on the same track.
    private var errorRecoveryCount = 0
    private var errorRecoveryWindowStart = 0L

    @Volatile
    private var currentSongId: Long? = null
    private var awaitingReady = mutableSetOf<String>()
    private val calibrationCoordinator = PartyHostCalibrationCoordinator(
        context = context,
        scope = scope,
        pauseHostPlayback = { if (controller?.playWhenReady == true) controller?.pause() },
        sendToClient = ::sendToClient,
    )

    // Out-of-band per-client SYNC_CHECK rounds (auto-resync watchdog), keyed
    // by memberId — distinct from the party-wide awaitingReady/checklist
    // cycle since these target one straggling guest without disturbing
    // anyone else.
    private val singleClientSyncAcks = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val resyncWatchdog = PartyHostResyncWatchdog(
        scope = scope,
        songCatalog = songCatalog,
        syncStatsTracker = syncStatsTracker,
        setStatus = ::setStatus,
        publishMembers = ::publishMembers,
        getHostPositionIfPlaying = {
            withContext(Dispatchers.Main) {
                if (controller?.playWhenReady == true) controller?.currentPosition else null
            }
        },
        sendToClient = ::sendToClient,
        isPreparing = { preparing },
        registerSyncAck = { memberId ->
            val ack = CompletableDeferred<Boolean>()
            synchronized(clients) { singleClientSyncAcks[memberId] = ack }
            ack
        },
        clearSyncAck = { memberId -> synchronized(clients) { singleClientSyncAcks.remove(memberId) } },
    )

    private class ClientConnection(
        val memberId: String,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: BufferedWriter,
    ) {
        var deviceName: String = "Unknown device"

        fun send(message: PartyMessage) {
            synchronized(writer) {
                writer.write(message.encode())
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Only the host's normal library playback has numeric media ids.
            val songId = mediaItem?.mediaId?.toLongOrNull() ?: return
            if (songId == currentSongId) return
            beginPrepareCycle(songId)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (pendingEngineEvents > 0) {
                pendingEngineEvents--
                return
            }
            val position = controller?.currentPosition ?: 0L
            if (preparing) {
                // User taps land on the player directly even while the engine
                // holds it for a scheduled start. Play would race ahead of the
                // party, so re-hold; pause means they changed their mind.
                if (playWhenReady) enginePause() else abortStartCycle(position)
                return
            }
            if (!hasGuests()) return
            if (playWhenReady) {
                beginResumeCycle(position)
            } else {
                broadcastAsync(PartyMessage.Pause(position))
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (pendingEngineSeeks > 0) {
                // The host drift loop's own corrective seek — not a user seek.
                pendingEngineSeeks--
                return
            }
            if (preparing || !hasGuests()) return
            val playing = controller?.playWhenReady == true
            if (playing) {
                beginResumeCycle(newPosition.positionMs)
            } else {
                broadcastAsync(PartyMessage.Pause(newPosition.positionMs))
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Player error: ${error.message}", error)
            recoverFromPlayerError()
        }
    }

    fun start(partyName: String, hostDeviceName: String) {
        this.partyName = partyName
        this.hostDeviceName = hostDeviceName
        val server = ServerSocket(0)
        serverSocket = server
        if (!wifiLock.isHeld) wifiLock.acquire()
        fileServer.start(SOCKET_READ_TIMEOUT_MS, false)
        nsd.registerService(partyName, server.localPort) { message ->
            update { it.copy(error = message) }
        }
        connectController()
        update {
            it.copy(role = PartyRole.HOST, partyName = partyName, members = emptyList(), error = null)
        }
        scope.launch { acceptLoop(server) }
        udpLocalPort = udpChannel.start(scope)
    }

    /** Assigns [role] to one guest for the bass/mid/treble party trick; NONE turns it off. */
    fun setGuestEqRole(memberId: String, role: PartyEqRole) {
        val client = synchronized(clients) { clients[memberId] } ?: return
        rosterStatus.setEqRole(memberId, role)
        runCatching { client.send(PartyMessage.SetEqRole(role)) }
        publishMembers()
    }

    fun stop() {
        if (wifiLock.isHeld) wifiLock.release()
        val snapshot = synchronized(clients) { clients.values.toList().also { clients.clear() } }
        rosterStatus.clear()
        udpChannel.clearEndpoints()
        PartyEqController.setRole(PartyEqRole.NONE)
        nsd.unregisterService()
        controller?.let { c ->
            ContextCompat.getMainExecutor(context).execute {
                c.removeListener(playerListener)
                c.setPlaybackParameters(PlaybackParameters.DEFAULT)
                c.release()
            }
        }
        controller = null
        // Socket work off the main thread; scope may already be tearing down.
        thread(name = "party-host-shutdown") {
            snapshot.forEach { client ->
                runCatching { client.send(PartyMessage.Bye) }
                runCatching { client.socket.close() }
            }
            runCatching { serverSocket?.close() }
            udpChannel.stop()
            runCatching { fileServer.stop() }
        }
        scope.cancel()
        update { PartyUiState() }
    }

    // ── Control channel ─────────────────────────────────────────────────────

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                break
            }
            scope.launch { handleClient(socket) }
        }
    }

    private fun handleClient(socket: Socket) {
        val memberId = UUID.randomUUID().toString()
        val client = ClientConnection(
            memberId = memberId,
            socket = socket,
            reader = socket.getInputStream().bufferedReader(),
            writer = socket.getOutputStream().bufferedWriter(),
        )
        try {
            val hello = PartyMessage.parse(client.reader.readLine() ?: return) as? PartyMessage.Hello
                ?: return
            if (hello.protocolVersion != PartyProtocol.VERSION) {
                client.send(PartyMessage.Error("Incompatible app version — update PaperPlayer on both devices"))
                return
            }
            client.deviceName = hello.deviceName
            synchronized(clients) { clients[memberId] = client }
            rosterStatus.setStatus(memberId, PartyMemberStatus.SYNCING)
            // The guest's own outgoing UDP socket is bound to hello.udpPort,
            // so datagrams from it arrive from (its IP, that port) — the same
            // pair we can build right now from the already-connected TCP
            // socket plus what it told us.
            udpChannel.registerEndpoint(memberId, InetSocketAddress(socket.inetAddress, hello.udpPort))
            client.send(
                PartyMessage.Welcome(
                    protocolVersion = PartyProtocol.VERSION,
                    partyName = partyName,
                    hostName = hostDeviceName,
                    httpPort = fileServer.listeningPort,
                    memberId = memberId,
                    udpPort = udpLocalPort,
                )
            )
            publishMembers()
            sendCurrentSongTo(client)

            while (true) {
                val line = client.reader.readLine() ?: break
                when (val message = PartyMessage.parse(line)) {
                    is PartyMessage.Ping -> client.send(
                        PartyMessage.Pong(message.seq, message.t0, SystemClock.elapsedRealtime())
                    )
                    is PartyMessage.Ready -> onGuestReady(client, message.songId)
                    is PartyMessage.SyncReady -> onSyncReady(client, message)
                    is PartyMessage.CalibrateRequest -> calibrationCoordinator.onCalibrateRequest(client.memberId, message.bandIndex)
                    is PartyMessage.CalibrateComplete -> calibrationCoordinator.onCalibrateComplete(client.memberId, message.bandIndex)
                    is PartyMessage.Bye -> break
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client $memberId dropped: ${e.message}")
        } finally {
            synchronized(clients) { clients.remove(memberId) }
            rosterStatus.remove(memberId)
            syncStatsTracker.remove(memberId)
            synchronized(clients) { awaitingReady.remove(memberId) }
            synchronized(clients) { singleClientSyncAcks.remove(memberId)?.complete(false) }
            resyncWatchdog.remove(memberId)
            udpChannel.removeEndpoint(memberId)
            runCatching { socket.close() }
            publishMembers()
        }
    }

    /** Late joiner while a song is already loaded: ship them the current track. */
    private fun sendCurrentSongTo(client: ClientConnection) {
        val songId = currentSongId ?: return
        scope.launch {
            val song = songCatalog.resolve(songId) ?: return@launch
            setStatus(client.memberId, PartyMemberStatus.DOWNLOADING)
            runCatching { client.send(songCatalog.prepareMessage(song, songCatalog.sha256(song))) }
        }
    }

    /**
     * Periodic guest telemetry: recalculates and stores drift via
     * [syncStatsTracker], then publishes and runs the resync watchdog check.
     * A null result means the sample was a dropped out-of-order/duplicate —
     * see [PartyHostSyncStatsTracker.record].
     */
    private fun onSyncStats(client: ClientConnection, stats: PartyMessage.SyncStats, receivedAtHostMs: Long) {
        val updated = syncStatsTracker.record(client.memberId, client.deviceName, stats, receivedAtHostMs) ?: return
        publishMembers()
        resyncWatchdog.check(client.memberId, client.deviceName, currentSongId, updated)
    }

    private fun onSyncReady(client: ClientConnection, message: PartyMessage.SyncReady) {
        if (message.songId != currentSongId) return
        // A stray, single-client SYNC_CHECK round (auto-resync watchdog) —
        // resolve it separately from the party-wide checklist below so it
        // doesn't touch awaitingReady or get mistaken for that cycle's ack.
        val strayAck = synchronized(clients) { singleClientSyncAcks.remove(client.memberId) }
        if (strayAck != null) {
            if (message.ok) {
                setStatus(client.memberId, PartyMemberStatus.READY)
            } else {
                Log.w(TAG, "${client.deviceName} failed resync check (${message.reason}); re-sending file")
                resendFile(client, message.songId)
            }
            strayAck.complete(message.ok)
            return
        }
        if (message.ok) {
            setStatus(client.memberId, PartyMemberStatus.READY)
            synchronized(clients) { awaitingReady.remove(client.memberId) }
        } else {
            Log.w(TAG, "${client.deviceName} failed sync check (${message.reason}); re-sending file")
            resendFile(client, message.songId)
        }
    }

    /** Guest's file failed the SHA-256 check during a sync round; ship it again — its READY releases whichever wait it was blocking. */
    private fun resendFile(client: ClientConnection, songId: Long) {
        setStatus(client.memberId, PartyMemberStatus.DOWNLOADING)
        scope.launch {
            val song = songCatalog.resolve(songId) ?: return@launch
            runCatching { client.send(songCatalog.prepareMessage(song, songCatalog.sha256(song))) }
        }
    }

    private fun onGuestReady(client: ClientConnection, songId: Long) {
        if (songId != currentSongId) return
        setStatus(client.memberId, PartyMemberStatus.READY)
        val stillPreparing = synchronized(clients) {
            awaitingReady.remove(client.memberId)
            preparing
        }
        if (!stillPreparing) {
            // Mid-song late joiner: start them where the host will be at the
            // scheduled instant (the host keeps playing, so lead-compensate).
            scope.launch {
                val position = withContext(Dispatchers.Main) {
                    if (controller?.playWhenReady == true) controller?.currentPosition else null
                } ?: return@launch
                val at = SystemClock.elapsedRealtime() + RESUME_LEAD_MS
                runCatching {
                    client.send(PartyMessage.Start(songId, position + RESUME_LEAD_MS, at))
                }
                resyncWatchdog.markStarted(client.memberId)
                setStatus(client.memberId, PartyMemberStatus.PLAYING)
            }
        }
    }

    // ── Playback take-over ──────────────────────────────────────────────────

    private fun connectController() {
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

    /** Main thread only. */
    private fun beginPrepareCycle(songId: Long) {
        currentSongId = songId
        if (!hasGuests()) return
        preparing = true
        stopHostDriftLoop()
        enginePause()
        // Auto-advance plays gaplessly into the new track before the pause
        // lands, so the host is already a few hundred ms in. Pull it back to
        // the announced position 0.
        controller?.seekTo(0L)
        startCycle?.cancel()
        startCycle = scope.launch {
            val song = songCatalog.resolve(songId)
            if (song == null) {
                Log.w(TAG, "Song $songId not found; releasing playback")
                withContext(Dispatchers.Main) {
                    preparing = false
                    enginePlay()
                }
                return@launch
            }
            val sha256 = songCatalog.sha256(song)
            synchronized(clients) { awaitingReady = clients.keys.toMutableSet() }
            setAllStatuses(PartyMemberStatus.DOWNLOADING)
            broadcast(songCatalog.prepareMessage(song, sha256))
            awaitReadySet(READY_TIMEOUT_MS)
            runStartChecklist(songId, sha256, positionMs = 0L, leadMs = TRACK_START_LEAD_MS)
        }
    }

    /**
     * Main thread only. Resume or seek-while-playing: hold the host, pin it to
     * the exact position announced to the guests (a few ms of audio slip out
     * between the play event and the pause landing), and run the checklist.
     */
    private fun beginResumeCycle(positionMs: Long) {
        val songId = currentSongId ?: return
        preparing = true
        stopHostDriftLoop()
        enginePause()
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        startCycle?.cancel()
        startCycle = scope.launch {
            val sha256 = songCatalog.resolve(songId)?.let { songCatalog.sha256(it) } ?: ""
            runStartChecklist(songId, sha256, positionMs, leadMs = TRACK_START_LEAD_MS)
        }
    }

    /**
     * Main thread only. The user paused during a hold: cancel the scheduled
     * start (checklist or countdown, wherever it is) and pause the party.
     */
    private fun abortStartCycle(positionMs: Long) {
        startCycle?.cancel()
        startCycle = null
        preparing = false
        stopHostDriftLoop()
        update { it.copy(startsInMs = null) }
        broadcastAsync(PartyMessage.Pause(positionMs))
    }

    /** Main thread only. Also clears a drift nudge so the host resumes at 1x. */
    private fun stopHostDriftLoop() {
        hostDriftJob?.cancel()
        hostDriftJob = null
        controller?.setPlaybackParameters(PlaybackParameters.DEFAULT)
    }

    /**
     * Main thread only. Re-prepares and re-seeks to where the announced
     * timeline says the party should be right now, so a transient player
     * error clears itself instead of leaving the host silent until the next
     * song. Gives up after a burst of repeated errors on the same track.
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
            stopHostDriftLoop()
            update { it.copy(error = "Playback error on this track") }
            return
        }
        c.prepare()
        if (hostDriftJob?.isActive == true) {
            val expected = lastStartPositionMs + (now - lastStartAtHostMs)
            pendingEngineSeeks++
            c.seekTo(expected + SEEK_LEAD_MS)
        }
    }

    /**
     * Pre-start checklist, run before every scheduled start: guests re-verify
     * their file against [sha256], re-measure the clock offset with a fresh
     * ping round, and pre-seek to [positionMs]; a guest whose file is missing
     * or corrupt gets it re-sent and rejoins via its READY. Then everyone —
     * host included — starts at the same host-clock instant.
     */
    private suspend fun runStartChecklist(songId: Long, sha256: String, positionMs: Long, leadMs: Long) {
        synchronized(clients) { awaitingReady = clients.keys.toMutableSet() }
        setAllStatuses(PartyMemberStatus.SYNCING)
        broadcast(PartyMessage.SyncCheck(songId, sha256, positionMs))
        awaitReadySet(SYNC_CHECK_TIMEOUT_MS)
        val startAt = SystemClock.elapsedRealtime() + leadMs
        Log.i(TAG, "Scheduled start: song $songId at ${positionMs}ms in ${leadMs}ms (host clock $startAt)")
        broadcast(PartyMessage.Start(songId, positionMs, startAt))
        syncStatsTracker.clearAll()
        resyncWatchdog.markStartedForAll(synchronized(clients) { clients.keys.toList() })
        setAllStatuses(PartyMemberStatus.PLAYING)
        hostPlayAt(startAt, positionMs)
    }

    private suspend fun awaitReadySet(timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (synchronized(clients) { awaitingReady.isEmpty() }) break
            delay(200L)
        }
    }

    /**
     * Plays the host's own audio at the announced host-clock instant so it
     * lands together with the guests' scheduled starts. Runs inside the start
     * cycle so an abort cancels it.
     */
    private suspend fun hostPlayAt(atHostElapsedMs: Long, startPositionMs: Long) {
        // Tick the countdown down to the last ~150ms, then hand over to the
        // precise start below.
        while (true) {
            val remaining = atHostElapsedMs - SystemClock.elapsedRealtime()
            if (remaining <= 150) break
            update { it.copy(startsInMs = remaining) }
            delay(minOf(remaining - 120, 100L))
        }
        update { it.copy(startsInMs = null) }
        withContext(Dispatchers.Main) {
            // Burn the last few milliseconds for an on-the-dot start.
            @Suppress("ControlFlowWithEmptyBody")
            while (SystemClock.elapsedRealtime() < atHostElapsedMs) { }
            preparing = false
            enginePlay()
            startHostDriftLoop(startPositionMs, atHostElapsedMs)
        }
    }

    /**
     * The host is the fixed reference the whole party matches, so unlike the
     * guest drift loop it never nudges or seeks itself — it just samples its
     * own real position every tick and broadcasts it (HOST_SYNC) so every
     * guest can chase the host's actual playback instead of an idealized
     * start-position + elapsed-time formula.
     */
    private fun startHostDriftLoop(startPositionMs: Long, startAtHostMs: Long) {
        hostDriftJob?.cancel()
        lastStartPositionMs = startPositionMs
        lastStartAtHostMs = startAtHostMs
        lastKnownHostPositionMs = startPositionMs
        lastKnownHostSampleAtMs = startAtHostMs
        errorRecoveryCount = 0
        hostDriftJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(DRIFT_CHECK_INTERVAL_MS)
                val c = controller ?: break
                if (preparing) break
                if (!c.isPlaying) continue
                // If this dies, HOST_SYNC stops going out entirely and every
                // guest loses its live reference for the rest of the song —
                // higher blast radius than the guest-side equivalent, so the
                // same one-bad-tick-shouldn't-end-the-song guard applies here.
                try {
                    val nowHost = SystemClock.elapsedRealtime()
                    val expected = startPositionMs + (nowHost - startAtHostMs)
                    val drift = c.currentPosition - expected
                    lastKnownHostPositionMs = c.currentPosition
                    lastKnownHostSampleAtMs = nowHost
                    udpChannel.broadcastAsync(
                        PartyMessage.HostSync(
                            atHostElapsedMs = nowHost,
                            positionMs = c.currentPosition,
                        ),
                        scope,
                    )
                    // The host's own row on the sync dashboard — informational
                    // only; it's never acted on since the host doesn't self-correct.
                    update {
                        it.copy(
                            syncDebug = it.syncDebug.copy(
                                lastDriftMs = drift,
                                playbackSpeed = 1f,
                                driftHistory = (it.syncDebug.driftHistory + drift).takeLast(DRIFT_HISTORY_SIZE),
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Host drift tick failed, retrying next tick: ${e.message}")
                }
            }
        }
    }

    /** Main thread only; counts a listener event only when state actually flips. */
    private fun enginePause() {
        val c = controller ?: return
        if (c.playWhenReady) {
            pendingEngineEvents++
            c.pause()
        }
    }

    /** Main thread only. */
    private fun enginePlay() {
        val c = controller ?: return
        if (!c.playWhenReady) {
            pendingEngineEvents++
            c.play()
        }
    }

    // ── Roster & broadcast helpers ──────────────────────────────────────────

    private fun hasGuests(): Boolean = synchronized(clients) { clients.isNotEmpty() }

    private fun broadcast(message: PartyMessage) {
        val snapshot = synchronized(clients) { clients.values.toList() }
        snapshot.forEach { client -> runCatching { client.send(message) } }
    }

    private fun broadcastAsync(message: PartyMessage) {
        scope.launch { broadcast(message) }
    }

    /** Sends to one specific guest by id, swallowing a failure the same way [broadcast] does. */
    private fun sendToClient(memberId: String, message: PartyMessage) {
        val client = synchronized(clients) { clients[memberId] } ?: return
        runCatching { client.send(message) }
    }

    private fun setStatus(memberId: String, status: PartyMemberStatus) {
        rosterStatus.setStatus(memberId, status)
        publishMembers()
    }

    private fun setAllStatuses(status: PartyMemberStatus) {
        rosterStatus.setAllStatuses(status)
        publishMembers()
    }

    private fun publishMembers() {
        val members = synchronized(clients) {
            clients.values.map { client ->
                PartyMember(
                    id = client.memberId,
                    name = client.deviceName,
                    status = rosterStatus.status(client.memberId),
                    stats = syncStatsTracker.snapshot(client.memberId),
                    eqRole = rosterStatus.eqRole(client.memberId),
                )
            }
        }
        update { it.copy(members = members) }
    }

    private companion object {
        const val TAG = "PartyHostEngine"
        const val READY_TIMEOUT_MS = 15_000L

        /**
         * Ceiling for the pre-start checklist (ping round is ~1s; the slack is
         * for a guest that has to re-download a corrupt file). The happy path
         * exits as soon as every guest acks.
         */
        const val SYNC_CHECK_TIMEOUT_MS = 10_000L

        /** Countdown before any scheduled start (new track, resume, seek) — settle + drama. */
        const val TRACK_START_LEAD_MS = 5_000L

        /** Short lead for catching a late joiner up mid-song; the party keeps playing. */
        const val RESUME_LEAD_MS = 1_500L

        const val SOCKET_READ_TIMEOUT_MS = 30_000

        // Host drift loop: measurement/reporting only (see startHostDriftLoop).
        const val DRIFT_CHECK_INTERVAL_MS = 500L
        const val SEEK_LEAD_MS = 60L
        const val DRIFT_HISTORY_SIZE = 60

        /** Player-error recovery: retries within this window count toward the cap below. */
        const val ERROR_WINDOW_MS = 10_000L
        const val MAX_ERROR_RECOVERIES = 3
    }
}
