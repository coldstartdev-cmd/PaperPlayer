package com.jp.paperplayer.party

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * UDP transport for the guest side of HOST_SYNC (in) and SYNC_STATS (out) —
 * see [PartyHostUdpChannel] for why this travels off the TCP control socket.
 * Runs for the life of the party session, independent of TCP reconnects — a
 * stray datagram from before a reconnect (or after [stop] has already closed
 * the socket) is just a no-op parse/dispatch once the socket is closed.
 */
class PartyGuestUdpChannel(
    private val onHostSync: (PartyMessage.HostSync) -> Unit,
) {
    private var socket: DatagramSocket? = null

    @Volatile
    private var hostEndpoint: InetSocketAddress? = null

    /** Opens the socket and launches the receive loop on [scope]; returns the bound local port. */
    fun start(scope: CoroutineScope): Int {
        val udp = DatagramSocket(0)
        socket = udp
        scope.launch { receiveLoop(udp) }
        return udp.localPort
    }

    fun setHostEndpoint(endpoint: InetSocketAddress) {
        hostEndpoint = endpoint
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(UDP_BUFFER_SIZE)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                break
            }
            val message = PartyMessage.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
            if (message is PartyMessage.HostSync) onHostSync(message)
        }
    }

    /**
     * Fire-and-forget, sent [UDP_SEND_REDUNDANCY] times: UDP has no
     * retransmission, so a lost SYNC_STATS is just gone. A cheap duplicate
     * send roughly squares the effective loss probability for the tick.
     */
    fun send(message: PartyMessage) {
        val socket = socket ?: return
        val endpoint = hostEndpoint ?: return
        val bytes = message.encode().toByteArray(Charsets.UTF_8)
        repeat(UDP_SEND_REDUNDANCY) {
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, endpoint)) }
        }
    }

    fun sendAsync(message: PartyMessage, scope: CoroutineScope) {
        scope.launch { send(message) }
    }

    fun stop() {
        runCatching { socket?.close() }
        hostEndpoint = null
    }

    private companion object {
        /** Generous for a small JSON payload (HOST_SYNC/SYNC_STATS are well under 300 bytes). */
        const val UDP_BUFFER_SIZE = 2048

        /** Duplicate sends per SYNC_STATS tick — UDP has no retransmission, so this is the cheap substitute. */
        const val UDP_SEND_REDUNDANCY = 2
    }
}
