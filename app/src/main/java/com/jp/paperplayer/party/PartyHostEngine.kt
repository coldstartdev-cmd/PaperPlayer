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
import com.jp.paperplayer.model.data.PartyMemberSyncStats
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.model.data.SyncHealth
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.scanner.MusicScanner
import com.jp.paperplayer.service.PlaybackService
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
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
import kotlinx.coroutines.withTimeoutOrNull

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
    private val fileServer = PartyFileServer(context) { songId ->
        runBlocking { resolveSong(songId) }
    }

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    // HOST_SYNC (out) and SYNC_STATS (in) travel over UDP, not the TCP
    // control socket — a missed tick is superseded by the next one 500ms
    // later, so it's not worth a dropped packet head-of-line-blocking
    // whatever else is queued on the TCP stream. Keyed by memberId, matching
    // each guest's UDP-listening endpoint as announced in its HELLO.
    private val udpEndpoints = mutableMapOf<String, InetSocketAddress>()
    private val clients = mutableMapOf<String, ClientConnection>()
    private val statuses = mutableMapOf<String, PartyMemberStatus>()
    private val memberStats = mutableMapOf<String, PartyMemberSyncStats>()
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
    private var songCache: Map<Long, Song>? = null
    private val hashCache = mutableMapOf<Long, String>()
    private var calibrating = false
    private var calibratingMemberId: String? = null
    private var calibratingBandIndex = -1
    private var calibrateCompleteAck: CompletableDeferred<Unit>? = null

    // Bass/mid/treble "distributed speaker" role assigned to each guest —
    // purely a fun/cosmetic layer, kept separate from the sync-critical
    // maps above. Tracked host-side only so the dashboard can show what
    // was last sent; the guest is the source of truth for whether it
    // actually applied.
    private val eqRoles = mutableMapOf<String, PartyEqRole>()

    // Out-of-band per-client SYNC_CHECK rounds (auto-resync watchdog), keyed
    // by memberId — distinct from the party-wide awaitingReady/checklist
    // cycle since these target one straggling guest without disturbing
    // anyone else.
    private val singleClientSyncAcks = mutableMapOf<String, CompletableDeferred<Boolean>>()

    // elapsedRealtime of the last START sent to each guest (party-wide,
    // late-join, or a targeted resync) — the watchdog won't act again until
    // RESYNC_GRACE_PERIOD_MS has passed, since every scheduled start has its
    // own few-second settling transient (host/guest start-latency warm-up,
    // nudge/seek corrections ramping down) that looks like "worsening" if
    // judged too early. This doubles as the resync rate limit, since a
    // resync itself is a start.
    private val lastStartedAtMs = mutableMapOf<String, Long>()

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
        val udp = DatagramSocket(0)
        udpSocket = udp
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
        scope.launch { udpReceiveLoop(udp) }
    }

    /** Assigns [role] to one guest for the bass/mid/treble party trick; NONE turns it off. */
    fun setGuestEqRole(memberId: String, role: PartyEqRole) {
        val client = synchronized(clients) { clients[memberId] } ?: return
        synchronized(eqRoles) { eqRoles[memberId] = role }
        runCatching { client.send(PartyMessage.SetEqRole(role)) }
        publishMembers()
    }

    fun stop() {
        if (wifiLock.isHeld) wifiLock.release()
        val snapshot = synchronized(clients) { clients.values.toList().also { clients.clear() } }
        synchronized(statuses) { statuses.clear() }
        synchronized(udpEndpoints) { udpEndpoints.clear() }
        synchronized(eqRoles) { eqRoles.clear() }
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
            runCatching { udpSocket?.close() }
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

    /**
     * Receives SYNC_STATS datagrams and routes each to the guest it came
     * from, matched by the (address, port) it announced in HELLO — UDP is
     * connectionless, so there's no socket to key off like the TCP side has.
     * A datagram from an address we don't recognize (guest hasn't finished
     * HELLO/WELCOME yet, or already left) is silently dropped.
     */
    private fun udpReceiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(UDP_BUFFER_SIZE)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                break
            }
            val receivedAtHostMs = SystemClock.elapsedRealtime()
            val message = PartyMessage.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
            if (message !is PartyMessage.SyncStats) continue
            val sender = InetSocketAddress(packet.address, packet.port)
            val memberId = synchronized(udpEndpoints) { udpEndpoints.entries.find { it.value == sender }?.key } ?: continue
            val client = synchronized(clients) { clients[memberId] } ?: continue
            onSyncStats(client, message, receivedAtHostMs)
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
            synchronized(statuses) { statuses[memberId] = PartyMemberStatus.SYNCING }
            // The guest's own outgoing UDP socket is bound to hello.udpPort,
            // so datagrams from it arrive from (its IP, that port) — the same
            // pair we can build right now from the already-connected TCP
            // socket plus what it told us.
            synchronized(udpEndpoints) {
                udpEndpoints[memberId] = InetSocketAddress(socket.inetAddress, hello.udpPort)
            }
            client.send(
                PartyMessage.Welcome(
                    protocolVersion = PartyProtocol.VERSION,
                    partyName = partyName,
                    hostName = hostDeviceName,
                    httpPort = fileServer.listeningPort,
                    memberId = memberId,
                    udpPort = udpSocket?.localPort ?: 0,
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
                    is PartyMessage.CalibrateRequest -> onCalibrateRequest(client, message.bandIndex)
                    is PartyMessage.CalibrateComplete -> onCalibrateComplete(client, message.bandIndex)
                    is PartyMessage.Bye -> break
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client $memberId dropped: ${e.message}")
        } finally {
            synchronized(clients) { clients.remove(memberId) }
            synchronized(statuses) { statuses.remove(memberId) }
            synchronized(memberStats) { memberStats.remove(memberId) }
            synchronized(clients) { awaitingReady.remove(memberId) }
            synchronized(clients) { singleClientSyncAcks.remove(memberId)?.complete(false) }
            synchronized(lastStartedAtMs) { lastStartedAtMs.remove(memberId) }
            synchronized(udpEndpoints) { udpEndpoints.remove(memberId) }
            synchronized(eqRoles) { eqRoles.remove(memberId) }
            runCatching { socket.close() }
            publishMembers()
        }
    }

    /** Late joiner while a song is already loaded: ship them the current track. */
    private fun sendCurrentSongTo(client: ClientConnection) {
        val songId = currentSongId ?: return
        scope.launch {
            val song = resolveSong(songId) ?: return@launch
            setStatus(client.memberId, PartyMemberStatus.DOWNLOADING)
            runCatching { client.send(prepareMessage(song, sha256Of(song))) }
        }
    }

    /**
     * Periodic guest telemetry: accumulate the (recalculated) drift history
     * host-side and publish, plus track the gap between consecutive samples
     * on the host's own timeline (via hostAtMs) — a much-larger-than-expected
     * gap means a delayed/dropped SYNC_STATS message, i.e. a control-channel
     * hiccup, which the drift figure alone can't distinguish from genuine
     * audio drift.
     *
     * The drift fed into history/health isn't the guest's self-reported
     * figure — it's recomputed against the host's own authoritative timeline
     * ([hostVerifiedExpectedPositionMs]), since the guest's own "expected"
     * position reflects whatever it last heard, which can be stale if it
     * missed recent HOST_SYNC updates during a network hiccup.
     */
    private fun onSyncStats(client: ClientConnection, stats: PartyMessage.SyncStats, receivedAtHostMs: Long) {
        // Anchor the verification on the host's own receipt clock (minus
        // half the guest's measured RTT, approximating one-way transit)
        // rather than the guest's self-computed hostAtMs. That value bakes
        // in the guest's clock-offset ESTIMATE, which wobbles a few/tens of
        // ms between ping refreshes on its own — comparing against it isn't
        // actually independent verification, it's comparing the guest
        // against a slightly different snapshot of its own offset estimate,
        // which fires false positives on a perfectly healthy connection.
        val hostAnchorMs = receivedAtHostMs - stats.rttMs / 2
        val verifiedExpected = hostVerifiedExpectedPositionMs(hostAnchorMs, stats.latencyTrimMs)
        val recalculatedDrift = verifiedExpected?.let { stats.actualPositionMs - it }
            ?: (stats.actualPositionMs - stats.expectedPositionMs)
        val updated = synchronized(memberStats) {
            val previous = memberStats[client.memberId]
            // SYNC_STATS travels over UDP with each tick sent twice for loss
            // resilience — nothing stops a duplicate or a reordered earlier
            // tick from arriving after a newer one already landed. Comparing
            // against the guest's own deviceAtMs (monotonic on that guest)
            // catches both: a stale actualPositionMs would otherwise get
            // diffed against the host's *current* verified-expected position,
            // manufacturing a drift spike that was never real.
            if (previous != null && stats.deviceAtMs <= previous.deviceAtMs) {
                Log.d(
                    TAG,
                    "${client.deviceName}: dropping out-of-order/duplicate SYNC_STATS " +
                        "(deviceAtMs=${stats.deviceAtMs} <= last accepted ${previous.deviceAtMs})",
                )
                return
            }
            val history = ((previous?.driftHistory ?: emptyList()) + recalculatedDrift).takeLast(DRIFT_HISTORY_SIZE)
            val gapMs = previous?.let { stats.hostAtMs - it.hostAtMs } ?: 0L
            val networkGaps = (previous?.networkGapCount ?: 0) + if (gapMs > NETWORK_GAP_THRESHOLD_MS) 1 else 0
            val next = PartyMemberSyncStats(
                deviceAtMs = stats.deviceAtMs,
                hostAtMs = stats.hostAtMs,
                expectedPositionMs = stats.expectedPositionMs,
                actualPositionMs = stats.actualPositionMs,
                rttMs = stats.rttMs,
                playbackSpeed = stats.playbackSpeed,
                seekCorrections = stats.seekCorrections,
                nudgeCorrections = stats.nudgeCorrections,
                latencyTrimMs = stats.latencyTrimMs,
                driftHistory = history,
                lastSampleGapMs = gapMs,
                networkGapCount = networkGaps,
                hostVerifiedExpectedPositionMs = verifiedExpected,
                hostResyncCount = previous?.hostResyncCount ?: 0,
            )
            memberStats[client.memberId] = next
            next
        }
        Log.d(
            TAG,
            "${client.deviceName}: guestDrift=${stats.actualPositionMs - stats.expectedPositionMs}ms " +
                "verifiedDrift=$recalculatedDrift ms (anchor=${if (verifiedExpected != null) "host" else "guest-fallback"}) " +
                "health=${updated.syncHealth} historySize=${updated.driftHistory.size} gapMs=${updated.lastSampleGapMs}",
        )
        publishMembers()
        maybeResync(client, updated)
    }

    /**
     * Where the host's own timeline says a guest should be at [hostAtMs] —
     * extrapolated from the host's own most recent real position sample
     * ([lastKnownHostPositionMs]/[lastKnownHostSampleAtMs], refreshed every
     * drift tick), independent of that guest's self-reported view. Not
     * extrapolated from [lastStartAtHostMs] (the last scheduled start,
     * which can be minutes ago): the host's system clock and its audio
     * hardware clock are different physical clocks, and even a small skew
     * between them would accumulate into a real gap over that long a
     * window, showing up as "drift" that was never actually audible.
     *
     * Includes [latencyTrimMs] deliberately: the guest's own drift loop
     * nudges/seeks its *raw position* to hit hostPosition + trim, so a
     * correctly-trimmed nonzero value is expected to show up as exactly
     * that much position offset once locked on — it's not an error. Leaving
     * trim out here meant a well-calibrated guest (say -70ms) always read as
     * "drifting" by roughly its own trim amount, while an *un*calibrated
     * guest (trim=0) that's actually audibly wrong read as "in sync" just
     * because raw position happened to match the host's with no offset.
     * This is a position-loop health check — "is the guest tracking its own
     * target" — not a check of whether that target's trim is acoustically
     * correct, which position data can never see (that's what the chirp
     * calibration is for).
     *
     * Null when there's no live epoch to check against: the host is
     * mid-transition ([preparing]), or [hostAtMs] predates the current
     * epoch (a stale/out-of-order report from just before the last track
     * change, resume, or seek).
     */
    private fun hostVerifiedExpectedPositionMs(hostAtMs: Long, latencyTrimMs: Long): Long? {
        if (preparing || hostDriftJob?.isActive != true) return null
        if (hostAtMs < lastStartAtHostMs) return null
        return lastKnownHostPositionMs + (hostAtMs - lastKnownHostSampleAtMs) + latencyTrimMs
    }

    /**
     * Watchdog: a guest whose recalculated drift trend says it's genuinely
     * falling out of sync (not a single noisy sample — see [SyncHealth])
     * gets forced back onto the timeline the same way a late joiner does:
     * fresh clock offset, pre-seek, scheduled start. Its own incremental
     * nudge/seek loop may not be enough to recover on its own if the cause
     * is a stale clock offset or a stuck player.
     *
     * Gated on [RESYNC_GRACE_PERIOD_MS] since the guest's last START (party
     * start, late join, or a previous resync) rather than just on
     * [SyncHealth]: every scheduled start has its own few-second settling
     * transient (host/guest start-latency warm-up, nudge/seek corrections
     * ramping down before they've caught up), and judging that transient too
     * early reads as "still falling out of sync" — which resyncs again,
     * which starts a new transient, forever. This was firing resyncs back to
     * back before the previous settling attempt had a chance to finish.
     *
     * Gated on [PartyFeatureFlags.HOST_ASSISTED_RESYNC_ENABLED] — off by
     * default. The guest's own nudge/seek loop chasing HOST_SYNC is the
     * proven baseline; this watchdog is a layer on top of it whose accuracy
     * can only be judged by ear, which made it hard to tune with confidence.
     */
    private fun maybeResync(client: ClientConnection, stats: PartyMemberSyncStats) {
        if (!PartyFeatureFlags.HOST_ASSISTED_RESYNC_ENABLED) return
        if (stats.syncHealth != SyncHealth.FALLING_OUT_OF_SYNC) return
        if (preparing) {
            Log.d(TAG, "${client.deviceName}: FALLING_OUT_OF_SYNC but host is preparing — skipping resync check")
            return
        }
        val songId = currentSongId ?: return
        val now = SystemClock.elapsedRealtime()
        val lastStarted = synchronized(lastStartedAtMs) { lastStartedAtMs[client.memberId] }
        val sinceStart = lastStarted?.let { now - it }
        if (lastStarted != null && sinceStart!! < RESYNC_GRACE_PERIOD_MS) {
            Log.d(
                TAG,
                "${client.deviceName}: FALLING_OUT_OF_SYNC (verifiedDrift=${stats.verifiedDriftMs}ms) but started " +
                    "${sinceStart}ms ago, < ${RESYNC_GRACE_PERIOD_MS}ms grace — letting it settle instead of resyncing",
            )
            return
        }
        synchronized(memberStats) {
            memberStats[client.memberId]?.let {
                memberStats[client.memberId] = it.copy(hostResyncCount = it.hostResyncCount + 1)
            }
        }
        publishMembers()
        Log.i(
            TAG,
            "${client.deviceName}: RESYNC #${stats.hostResyncCount + 1} triggered — verifiedDrift=${stats.verifiedDriftMs}ms " +
                "driftHistory=${stats.driftHistory} sinceLastStart=${sinceStart}ms",
        )
        scope.launch { resyncStragglingGuest(client, songId) }
    }

    /**
     * Forces one guest back onto the announced timeline: a fresh SYNC_CHECK
     * (re-measures its clock offset, re-verifies the file, pre-seeks) then a
     * scheduled START — the same mechanism a late joiner goes through, but
     * triggered by detecting sustained drift instead of a fresh connection.
     * Scoped to this one client; the rest of the party, including the host's
     * own playback, is untouched.
     */
    private suspend fun resyncStragglingGuest(client: ClientConnection, songId: Long) {
        val position = withContext(Dispatchers.Main) {
            if (controller?.playWhenReady == true) controller?.currentPosition else null
        } ?: run {
            Log.w(TAG, "${client.deviceName}: resync aborted — host isn't playing")
            return
        }
        val sha256 = resolveSong(songId)?.let { sha256Of(it) } ?: ""
        setStatus(client.memberId, PartyMemberStatus.SYNCING)
        val ack = CompletableDeferred<Boolean>()
        synchronized(clients) { singleClientSyncAcks[client.memberId] = ack }
        Log.i(TAG, "${client.deviceName}: resync SYNC_CHECK sent, host position=${position}ms")
        runCatching { client.send(PartyMessage.SyncCheck(songId, sha256, position)) }
        val ok = withTimeoutOrNull(SYNC_CHECK_TIMEOUT_MS) { ack.await() } ?: false
        synchronized(clients) { singleClientSyncAcks.remove(client.memberId) }
        if (!ok) {
            Log.w(TAG, "${client.deviceName}: resync SYNC_CHECK failed or timed out after ${SYNC_CHECK_TIMEOUT_MS}ms")
            return
        }
        val freshPosition = withContext(Dispatchers.Main) {
            if (controller?.playWhenReady == true) controller?.currentPosition else null
        } ?: run {
            Log.w(TAG, "${client.deviceName}: resync aborted after SYNC_CHECK — host isn't playing")
            return
        }
        val at = SystemClock.elapsedRealtime() + RESUME_LEAD_MS
        Log.i(TAG, "${client.deviceName}: resync START sent, position=${freshPosition + RESUME_LEAD_MS}ms at host clock $at")
        runCatching { client.send(PartyMessage.Start(songId, freshPosition + RESUME_LEAD_MS, at)) }
        clearDriftHistory(client.memberId)
        markStarted(client.memberId)
        setStatus(client.memberId, PartyMemberStatus.PLAYING)
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
            val song = resolveSong(songId) ?: return@launch
            runCatching { client.send(prepareMessage(song, sha256Of(song))) }
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
                markStarted(client.memberId)
                setStatus(client.memberId, PartyMemberStatus.PLAYING)
            }
        }
    }

    /**
     * Emits the host calibration chirp at an announced instant so the guest can
     * measure the relative output latency. Music is paused (which propagates to
     * all guests) so the room is quiet during the chirps.
     */
    private fun onCalibrateRequest(client: ClientConnection, bandIndex: Int) {
        val ack = CompletableDeferred<Unit>()
        synchronized(this) {
            if (calibrating) {
                runCatching { client.send(PartyMessage.CalibrateDenied("Another device is calibrating — try again in a moment")) }
                return
            }
            calibrating = true
            calibratingMemberId = client.memberId
            calibratingBandIndex = bandIndex
            calibrateCompleteAck = ack
        }
        scope.launch {
            try {
                val band = Chirp.BANDS.getOrElse(bandIndex) { Chirp.BANDS[0] }
                withContext(Dispatchers.Main) {
                    // A plain pause (not enginePause) so the pause propagates to guests.
                    if (controller?.playWhenReady == true) controller?.pause()
                }
                val at = SystemClock.elapsedRealtime() + CALIBRATE_LEAD_MS
                runCatching { client.send(PartyMessage.CalibrateChirp(at, bandIndex)) }
                ExoChirpPlayer.playAt(context, Chirp.hostChirpPcm(band), Chirp.SAMPLE_RATE, at)
                // Wait for the guest's own CALIBRATE_COMPLETE instead of
                // guessing how long its chirp-and-record round takes — a
                // fixed estimate here previously raced the guest's actual
                // timing under real-world jitter (GC pauses, AudioRecord
                // setup variance, WiFi retransmits). The timeout is a safety
                // net only, for a guest that disconnects mid-round.
                withTimeoutOrNull(CALIBRATE_COMPLETE_TIMEOUT_MS) { ack.await() }
            } finally {
                synchronized(this@PartyHostEngine) {
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
    private fun onCalibrateComplete(client: ClientConnection, bandIndex: Int) {
        synchronized(this) {
            if (client.memberId == calibratingMemberId && bandIndex == calibratingBandIndex) {
                calibrateCompleteAck?.complete(Unit)
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
            val song = resolveSong(songId)
            if (song == null) {
                Log.w(TAG, "Song $songId not found; releasing playback")
                withContext(Dispatchers.Main) {
                    preparing = false
                    enginePlay()
                }
                return@launch
            }
            val sha256 = sha256Of(song)
            synchronized(clients) { awaitingReady = clients.keys.toMutableSet() }
            setAllStatuses(PartyMemberStatus.DOWNLOADING)
            broadcast(prepareMessage(song, sha256))
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
            val sha256 = resolveSong(songId)?.let { sha256Of(it) } ?: ""
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
        clearAllDriftHistory()
        markStartedForAll()
        setAllStatuses(PartyMemberStatus.PLAYING)
        hostPlayAt(startAt, positionMs)
    }

    /**
     * Drops every guest's retained drift history. Called whenever a fresh
     * scheduled start begins — party-wide (track change, resume, seek) or a
     * single targeted auto-resync: the old samples span a position
     * discontinuity, and mixing them with post-start data corrupts both
     * [SyncHealth]'s trend check and the trim-bias suggestion. Without this,
     * a *successful* resync kept re-triggering itself: ~30 stale elevated
     * samples were still dragging the "recent half" average down for up to
     * ~15s after the guest had already caught back up.
     */
    private fun clearAllDriftHistory() {
        synchronized(memberStats) {
            memberStats.keys.toList().forEach { id -> memberStats[id]?.let { memberStats[id] = it.copy(driftHistory = emptyList()) } }
        }
    }

    private fun clearDriftHistory(memberId: String) {
        synchronized(memberStats) {
            memberStats[memberId]?.let { memberStats[memberId] = it.copy(driftHistory = emptyList()) }
        }
    }

    /** Records that a guest was just sent a START — arms its settling grace period (see [lastStartedAtMs]). */
    private fun markStarted(memberId: String) {
        synchronized(lastStartedAtMs) { lastStartedAtMs[memberId] = SystemClock.elapsedRealtime() }
    }

    private fun markStartedForAll() {
        val now = SystemClock.elapsedRealtime()
        val ids = synchronized(clients) { clients.keys.toList() }
        synchronized(lastStartedAtMs) { ids.forEach { lastStartedAtMs[it] = now } }
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
                    broadcastUdpAsync(
                        PartyMessage.HostSync(
                            atHostElapsedMs = nowHost,
                            positionMs = c.currentPosition,
                        )
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

    private fun prepareMessage(song: Song, sha256: String): PartyMessage.Prepare {
        val sizeBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(song.uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        return PartyMessage.Prepare(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.duration,
            ext = song.filePath.substringAfterLast('.', "mp3"),
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }

    /** Cached per song; an unreadable file yields "" which disables hash checks. */
    private suspend fun sha256Of(song: Song): String = withContext(Dispatchers.IO) {
        synchronized(hashCache) { hashCache[song.id] }?.let { return@withContext it }
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(song.uri) ?: return@withContext ""
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        synchronized(hashCache) { hashCache[song.id] = hex }
        hex
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

    private suspend fun resolveSong(songId: Long): Song? {
        songCache?.get(songId)?.let { return it }
        val scanned = MusicScanner(context).scan().associateBy { it.id }
        songCache = scanned
        return scanned[songId]
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

    /**
     * Sends each datagram [UDP_SEND_REDUNDANCY] times: unlike the TCP
     * control socket, UDP has no retransmission, so a single lost HOST_SYNC
     * is just gone. A cheap duplicate send roughly squares the effective
     * loss probability for the tick at negligible bandwidth cost (these
     * payloads are well under 300 bytes).
     */
    private fun broadcastUdp(message: PartyMessage) {
        val socket = udpSocket ?: return
        val endpoints = synchronized(udpEndpoints) { udpEndpoints.values.toList() }
        val bytes = message.encode().toByteArray(Charsets.UTF_8)
        endpoints.forEach { endpoint ->
            repeat(UDP_SEND_REDUNDANCY) {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, endpoint)) }
            }
        }
    }

    private fun broadcastUdpAsync(message: PartyMessage) {
        scope.launch { broadcastUdp(message) }
    }

    private fun setStatus(memberId: String, status: PartyMemberStatus) {
        synchronized(statuses) { statuses[memberId] = status }
        publishMembers()
    }

    private fun setAllStatuses(status: PartyMemberStatus) {
        synchronized(statuses) { statuses.keys.forEach { statuses[it] = status } }
        publishMembers()
    }

    private fun publishMembers() {
        val members = synchronized(clients) {
            clients.values.map { client ->
                PartyMember(
                    id = client.memberId,
                    name = client.deviceName,
                    status = synchronized(statuses) {
                        statuses[client.memberId] ?: PartyMemberStatus.JOINING
                    },
                    stats = synchronized(memberStats) { memberStats[client.memberId] },
                    eqRole = synchronized(eqRoles) { eqRoles[client.memberId] } ?: PartyEqRole.NONE,
                )
            }
        }
        update { it.copy(members = members) }
    }

    private companion object {
        const val TAG = "PartyHostEngine"
        const val READY_TIMEOUT_MS = 15_000L

        /** Generous for a small JSON payload (HOST_SYNC/SYNC_STATS are well under 300 bytes). */
        const val UDP_BUFFER_SIZE = 2048

        /** Duplicate sends per HOST_SYNC tick — UDP has no retransmission, so this is the cheap substitute. */
        const val UDP_SEND_REDUNDANCY = 2

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

        /** Lead time announced before the host's calibration chirp. */
        const val CALIBRATE_LEAD_MS = 1_500L

        /** Safety net only: releases the busy lock even if a guest's CALIBRATE_COMPLETE never arrives. */
        const val CALIBRATE_COMPLETE_TIMEOUT_MS = 5_000L
        const val SOCKET_READ_TIMEOUT_MS = 30_000

        // Host drift loop: measurement/reporting only (see startHostDriftLoop).
        const val DRIFT_CHECK_INTERVAL_MS = 500L
        const val SEEK_LEAD_MS = 60L
        const val DRIFT_HISTORY_SIZE = 60

        /** Guest SYNC_STATS arrive every ~DRIFT_CHECK_INTERVAL_MS on a healthy connection; a gap past this suggests a delayed/dropped message rather than normal jitter. */
        const val NETWORK_GAP_THRESHOLD_MS = 1_500L

        /**
         * How long a guest gets to settle after any START (party start, late
         * join, or a previous resync) before the watchdog will act on it
         * again. Set past the ~10-20s natural convergence window observed
         * for the nudge/seek loop to lock on; shorter than that and the
         * watchdog catches normal settling mid-flight and mistakes it for a
         * persistent problem, re-triggering itself indefinitely.
         */
        const val RESYNC_GRACE_PERIOD_MS = 20_000L

        /** Player-error recovery: retries within this window count toward the cap below. */
        const val ERROR_WINDOW_MS = 10_000L
        const val MAX_ERROR_RECOVERIES = 3
    }
}
