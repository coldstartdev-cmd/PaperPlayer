package com.jp.paperplayer.party

import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One connected guest's TCP control socket. Not private so [PartyHostEngine] can hold references to it. */
internal class ClientConnection(
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

/**
 * TCP control-socket server for the host: accepts connections, validates
 * HELLO, and owns the three maps that today share one lock — `clients`,
 * `awaitingReady`, and `singleClientSyncAcks` — bundled together exactly as
 * before rather than split apart, since callers (the pre-start checklist,
 * the resync watchdog) rely on that bundling for consistent snapshots.
 * Per-message business logic (what a READY/SYNC_READY/CALIBRATE_* actually
 * *does*) stays with the caller via callbacks; this class only special-cases
 * the two protocol-level messages that are pure socket mechanics: PING
 * (self-contained echo) and BYE (ends the loop).
 */
class PartyHostControlServer(
    private val onHello: (client: ClientConnection, hello: PartyMessage.Hello) -> Unit,
    private val onReady: (client: ClientConnection, songId: Long) -> Unit,
    private val onSyncReady: (client: ClientConnection, message: PartyMessage.SyncReady) -> Unit,
    private val onCalibrateRequest: (client: ClientConnection, bandIndex: Int) -> Unit,
    private val onCalibrateComplete: (client: ClientConnection, bandIndex: Int) -> Unit,
    /** Roster/tracker/watchdog cleanup — the clients/awaitingReady/singleClientSyncAcks trio is already handled internally. */
    private val onClientGone: (memberId: String) -> Unit,
) {
    private val clients = mutableMapOf<String, ClientConnection>()
    private var awaitingReady = mutableSetOf<String>()

    // Out-of-band per-client SYNC_CHECK rounds (auto-resync watchdog), keyed
    // by memberId — distinct from the party-wide awaitingReady/checklist
    // cycle since these target one straggling guest without disturbing
    // anyone else.
    private val singleClientSyncAcks = mutableMapOf<String, CompletableDeferred<Boolean>>()

    fun start(server: ServerSocket, scope: CoroutineScope) {
        scope.launch { acceptLoop(server, scope) }
    }

    private fun acceptLoop(server: ServerSocket, scope: CoroutineScope) {
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
            onHello(client, hello)

            while (true) {
                val line = client.reader.readLine() ?: break
                when (val message = PartyMessage.parse(line)) {
                    is PartyMessage.Ping -> client.send(
                        PartyMessage.Pong(message.seq, message.t0, SystemClock.elapsedRealtime())
                    )
                    is PartyMessage.Ready -> onReady(client, message.songId)
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
            synchronized(clients) { awaitingReady.remove(memberId) }
            synchronized(clients) { singleClientSyncAcks.remove(memberId)?.complete(false) }
            onClientGone(memberId)
            runCatching { socket.close() }
        }
    }

    fun get(memberId: String): ClientConnection? = synchronized(clients) { clients[memberId] }

    fun hasGuests(): Boolean = synchronized(clients) { clients.isNotEmpty() }

    fun clientIds(): List<String> = synchronized(clients) { clients.keys.toList() }

    /** Snapshot of every connected client, for building the UI-facing roster. */
    fun snapshot(): List<ClientConnection> = synchronized(clients) { clients.values.toList() }

    fun broadcast(message: PartyMessage) {
        val snapshot = synchronized(clients) { clients.values.toList() }
        snapshot.forEach { client -> runCatching { client.send(message) } }
    }

    /** Sends to one specific guest by id, swallowing a failure the same way [broadcast] does. */
    fun sendTo(memberId: String, message: PartyMessage) {
        val client = get(memberId) ?: return
        runCatching { client.send(message) }
    }

    fun resetAwaitingToAllClients() {
        synchronized(clients) { awaitingReady = clients.keys.toMutableSet() }
    }

    fun removeAwaiting(memberId: String) {
        synchronized(clients) { awaitingReady.remove(memberId) }
    }

    fun isAwaitingEmpty(): Boolean = synchronized(clients) { awaitingReady.isEmpty() }

    fun registerSyncAck(memberId: String): CompletableDeferred<Boolean> {
        val ack = CompletableDeferred<Boolean>()
        synchronized(clients) { singleClientSyncAcks[memberId] = ack }
        return ack
    }

    /** Removes and returns the pending ack, if any — used both to resolve a stray SYNC_READY and to clear a timed-out one. */
    fun takeSyncAck(memberId: String): CompletableDeferred<Boolean>? =
        synchronized(clients) { singleClientSyncAcks.remove(memberId) }

    /** Snapshots and clears the roster in one step, for a clean shutdown handoff — see [PartyHostEngine.stop]. */
    fun snapshotAndClear(): List<ClientConnection> =
        synchronized(clients) { clients.values.toList().also { clients.clear() } }

    private companion object {
        const val TAG = "PartyHostControlServer"
    }
}
