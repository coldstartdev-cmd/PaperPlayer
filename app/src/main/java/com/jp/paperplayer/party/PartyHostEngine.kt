package com.jp.paperplayer.party

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jp.paperplayer.model.data.PartyMember
import com.jp.paperplayer.model.data.PartyMemberStatus
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartyUiState
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs a party as the host: advertises via mDNS, accepts guest control
 * connections, and maintains the member roster. Playback take-over and file
 * serving arrive in later phases.
 */
class PartyHostEngine(
    context: Context,
    private val update: ((PartyUiState) -> PartyUiState) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd = NsdHelper(context)

    private var serverSocket: ServerSocket? = null
    private val clients = mutableMapOf<String, ClientConnection>()
    private var hostDeviceName: String = ""
    private var partyName: String = ""

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

    fun start(partyName: String, hostDeviceName: String) {
        this.partyName = partyName
        this.hostDeviceName = hostDeviceName
        val server = ServerSocket(0)
        serverSocket = server
        nsd.registerService(partyName, server.localPort) { message ->
            update { it.copy(error = message) }
        }
        update {
            it.copy(role = PartyRole.HOST, partyName = partyName, members = emptyList(), error = null)
        }
        scope.launch { acceptLoop(server) }
    }

    fun stop() {
        clients.values.toList().forEach { client ->
            runCatching { client.send(PartyMessage.Bye) }
            runCatching { client.socket.close() }
        }
        synchronized(clients) { clients.clear() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        nsd.unregisterService()
        scope.cancel()
        update { PartyUiState() }
    }

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
            client.send(
                PartyMessage.Welcome(
                    protocolVersion = PartyProtocol.VERSION,
                    partyName = partyName,
                    hostName = hostDeviceName,
                    httpPort = 0,
                    memberId = memberId,
                )
            )
            publishMembers(joinedId = memberId, status = PartyMemberStatus.SYNCING)

            while (true) {
                val line = client.reader.readLine() ?: break
                when (val message = PartyMessage.parse(line)) {
                    is PartyMessage.Ping -> client.send(
                        PartyMessage.Pong(message.seq, message.t0, SystemClock.elapsedRealtime())
                    )
                    is PartyMessage.Bye -> break
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client $memberId dropped: ${e.message}")
        } finally {
            synchronized(clients) { clients.remove(memberId) }
            runCatching { socket.close() }
            publishMembers()
        }
    }

    private fun publishMembers(joinedId: String? = null, status: PartyMemberStatus = PartyMemberStatus.READY) {
        val members = synchronized(clients) {
            clients.values.map { client ->
                PartyMember(
                    id = client.memberId,
                    name = client.deviceName,
                    status = if (client.memberId == joinedId) status else PartyMemberStatus.READY,
                )
            }
        }
        update { it.copy(members = members) }
    }

    private companion object {
        const val TAG = "PartyHostEngine"
    }
}
