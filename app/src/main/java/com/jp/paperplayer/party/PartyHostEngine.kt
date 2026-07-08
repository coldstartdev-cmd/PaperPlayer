package com.jp.paperplayer.party

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.jp.paperplayer.model.data.PartyMember
import com.jp.paperplayer.model.data.PartyMemberStatus
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.scanner.MusicScanner
import com.jp.paperplayer.service.PlaybackService
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * playback, ships the file to all guests (PREPARE -> READY), then starts
 * everyone together (START). Pause/resume/seek propagate as they happen.
 */
class PartyHostEngine(
    private val context: Context,
    private val update: ((PartyUiState) -> PartyUiState) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd = NsdHelper(context)
    private val fileServer = PartyFileServer(context) { songId ->
        runBlocking { resolveSong(songId) }
    }

    private var serverSocket: ServerSocket? = null
    private val clients = mutableMapOf<String, ClientConnection>()
    private val statuses = mutableMapOf<String, PartyMemberStatus>()
    private var hostDeviceName: String = ""
    private var partyName: String = ""

    // Playback take-over. Controller calls stay on the main thread; socket
    // writes hop to the IO scope.
    private var controller: MediaController? = null
    private var pendingEngineEvents = 0
    private var preparing = false
    private var currentSongId: Long? = null
    private var awaitingReady = mutableSetOf<String>()
    private var songCache: Map<Long, Song>? = null
    private var calibrating = false

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
            if (preparing || !hasGuests()) return
            val position = controller?.currentPosition ?: 0L
            if (playWhenReady) {
                // Hold the host for a moment and start everyone at the same instant.
                val at = SystemClock.elapsedRealtime() + RESUME_LEAD_MS
                enginePause()
                broadcastAsync(PartyMessage.Resume(position, at))
                scheduleHostPlay(at)
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
            if (preparing || !hasGuests()) return
            val playing = controller?.playWhenReady == true
            if (playing) {
                val at = SystemClock.elapsedRealtime() + RESUME_LEAD_MS
                enginePause()
                broadcastAsync(PartyMessage.Resume(newPosition.positionMs, at))
                scheduleHostPlay(at)
            } else {
                broadcastAsync(PartyMessage.Pause(newPosition.positionMs))
            }
        }
    }

    fun start(partyName: String, hostDeviceName: String) {
        this.partyName = partyName
        this.hostDeviceName = hostDeviceName
        val server = ServerSocket(0)
        serverSocket = server
        fileServer.start(SOCKET_READ_TIMEOUT_MS, false)
        nsd.registerService(partyName, server.localPort) { message ->
            update { it.copy(error = message) }
        }
        connectController()
        update {
            it.copy(role = PartyRole.HOST, partyName = partyName, members = emptyList(), error = null)
        }
        scope.launch { acceptLoop(server) }
    }

    fun stop() {
        val snapshot = synchronized(clients) { clients.values.toList().also { clients.clear() } }
        synchronized(statuses) { statuses.clear() }
        nsd.unregisterService()
        controller?.let { c ->
            ContextCompat.getMainExecutor(context).execute {
                c.removeListener(playerListener)
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
            synchronized(statuses) { statuses[memberId] = PartyMemberStatus.SYNCING }
            client.send(
                PartyMessage.Welcome(
                    protocolVersion = PartyProtocol.VERSION,
                    partyName = partyName,
                    hostName = hostDeviceName,
                    httpPort = fileServer.listeningPort,
                    memberId = memberId,
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
                    is PartyMessage.CalibrateRequest -> onCalibrateRequest(client)
                    is PartyMessage.Bye -> break
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client $memberId dropped: ${e.message}")
        } finally {
            synchronized(clients) { clients.remove(memberId) }
            synchronized(statuses) { statuses.remove(memberId) }
            synchronized(clients) { awaitingReady.remove(memberId) }
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
            runCatching { client.send(prepareMessage(song)) }
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
                setStatus(client.memberId, PartyMemberStatus.PLAYING)
            }
        }
    }

    /**
     * Emits the host calibration chirp at an announced instant so the guest can
     * measure the relative output latency. Music is paused (which propagates to
     * all guests) so the room is quiet during the chirps.
     */
    private fun onCalibrateRequest(client: ClientConnection) {
        synchronized(this) {
            if (calibrating) {
                runCatching { client.send(PartyMessage.CalibrateDenied("Another device is calibrating — try again in a moment")) }
                return
            }
            calibrating = true
        }
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    // A plain pause (not enginePause) so the pause propagates to guests.
                    if (controller?.playWhenReady == true) controller?.pause()
                }
                val at = SystemClock.elapsedRealtime() + CALIBRATE_LEAD_MS
                runCatching { client.send(PartyMessage.CalibrateChirp(at)) }
                ChirpPlayer.playAt(Chirp.hostChirpPcm(), at)
                // Stay busy until the guest's own chirp (+tail) has passed.
                delay(CALIBRATE_LEAD_MS + 1_500L)
            } finally {
                synchronized(this@PartyHostEngine) { calibrating = false }
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
        enginePause()
        scope.launch {
            val song = resolveSong(songId)
            if (song == null) {
                Log.w(TAG, "Song $songId not found; releasing playback")
                withContext(Dispatchers.Main) {
                    preparing = false
                    enginePlay()
                }
                return@launch
            }
            synchronized(clients) { awaitingReady = clients.keys.toMutableSet() }
            setAllStatuses(PartyMemberStatus.DOWNLOADING)
            broadcast(prepareMessage(song))

            val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (synchronized(clients) { awaitingReady.isEmpty() }) break
                delay(200L)
            }

            // Scheduled start: everyone (host included) begins at the same host-clock instant.
            val startAt = SystemClock.elapsedRealtime() + TRACK_START_LEAD_MS
            broadcast(PartyMessage.Start(songId, 0L, startAt))
            setAllStatuses(PartyMemberStatus.PLAYING)
            scheduleHostPlay(startAt, clearPreparing = true)
        }
    }

    /**
     * Plays the host's own audio at the announced host-clock instant so it
     * lands together with the guests' scheduled starts.
     */
    private fun scheduleHostPlay(atHostElapsedMs: Long, clearPreparing: Boolean = false) {
        scope.launch {
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
                if (clearPreparing) preparing = false
                enginePlay()
            }
        }
    }

    private fun prepareMessage(song: Song): PartyMessage.Prepare {
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
        )
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
                )
            }
        }
        update { it.copy(members = members) }
    }

    private companion object {
        const val TAG = "PartyHostEngine"
        const val READY_TIMEOUT_MS = 15_000L

        /** Countdown before a new track starts everywhere — download settle + drama. */
        const val TRACK_START_LEAD_MS = 5_000L

        /** Short hold when the host resumes or seeks so guests can align. */
        const val RESUME_LEAD_MS = 1_500L

        /** Lead time announced before the host's calibration chirp. */
        const val CALIBRATE_LEAD_MS = 1_500L
        const val SOCKET_READ_TIMEOUT_MS = 30_000
    }
}
