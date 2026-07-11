package com.jp.paperplayer.party

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * UDP transport for the host side of HOST_SYNC (out) and SYNC_STATS (in) —
 * kept off the TCP control socket since a missed tick is superseded by the
 * next one 500ms later, so it's not worth a dropped packet head-of-line
 * blocking whatever else is queued on the TCP stream. Endpoints are keyed by
 * memberId, matching each guest's UDP-listening endpoint as announced in its
 * HELLO; a datagram from an address that isn't registered (guest hasn't
 * finished HELLO/WELCOME yet, or already left) is silently dropped.
 */
class PartyHostUdpChannel(
    private val onSyncStats: (memberId: String, stats: PartyMessage.SyncStats, receivedAtHostMs: Long) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private val endpoints = mutableMapOf<String, InetSocketAddress>()

    /** Opens the socket and launches the receive loop on [scope]; returns the bound local port. */
    fun start(scope: CoroutineScope): Int {
        val udp = DatagramSocket(0)
        socket = udp
        scope.launch { receiveLoop(udp) }
        return udp.localPort
    }

    fun registerEndpoint(memberId: String, endpoint: InetSocketAddress) {
        synchronized(endpoints) { endpoints[memberId] = endpoint }
    }

    fun removeEndpoint(memberId: String) {
        synchronized(endpoints) { endpoints.remove(memberId) }
    }

    fun clearEndpoints() {
        synchronized(endpoints) { endpoints.clear() }
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
            val receivedAtHostMs = SystemClock.elapsedRealtime()
            val message = PartyMessage.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
            if (message !is PartyMessage.SyncStats) continue
            val sender = InetSocketAddress(packet.address, packet.port)
            val memberId = synchronized(endpoints) { endpoints.entries.find { it.value == sender }?.key } ?: continue
            onSyncStats(memberId, message, receivedAtHostMs)
        }
    }

    /**
     * Sends each datagram [UDP_SEND_REDUNDANCY] times: unlike the TCP
     * control socket, UDP has no retransmission, so a single lost HOST_SYNC
     * is just gone. A cheap duplicate send roughly squares the effective
     * loss probability for the tick at negligible bandwidth cost (these
     * payloads are well under 300 bytes).
     */
    fun broadcast(message: PartyMessage) {
        val socket = socket ?: return
        val snapshot = synchronized(endpoints) { endpoints.values.toList() }
        val bytes = message.encode().toByteArray(Charsets.UTF_8)
        snapshot.forEach { endpoint ->
            repeat(UDP_SEND_REDUNDANCY) {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, endpoint)) }
            }
        }
    }

    fun broadcastAsync(message: PartyMessage, scope: CoroutineScope) {
        scope.launch { broadcast(message) }
    }

    fun stop() {
        runCatching { socket?.close() }
    }

    private companion object {
        /** Generous for a small JSON payload (HOST_SYNC/SYNC_STATS are well under 300 bytes). */
        const val UDP_BUFFER_SIZE = 2048

        /** Duplicate sends per HOST_SYNC tick — UDP has no retransmission, so this is the cheap substitute. */
        const val UDP_SEND_REDUNDANCY = 2
    }
}
