package com.jp.paperplayer.party

import android.util.Log
import com.jp.paperplayer.model.data.DiscoveredParty
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TCP control-socket transport for the guest: connects, sends HELLO, runs
 * the read loop, and owns the auto-reconnect state machine (WiFi power-save
 * drops, AP roaming). Message routing stays with the caller via [onMessage]
 * — this class only special-cases the two protocol-level messages that
 * change the loop's own control flow: BYE (graceful end) and ERROR (host
 * rejected the join; must NOT trigger a reconnect, unlike every other kind
 * of disconnect).
 */
class PartyGuestControlChannel(
    private val onMessage: (PartyMessage) -> Unit,
    private val onRejected: (reason: String) -> Unit,
    private val onJoinFailed: (partyServiceName: String) -> Unit,
    /** [willRetry] tells the caller whether a reconnect attempt has already been scheduled. */
    private val onDisconnected: (willRetry: Boolean) -> Unit,
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var udpLocalPort = 0

    // Auto-reconnect after a dropped connection (WiFi power-save, AP roaming).
    @Volatile
    private var leaving = false
    private var lastParty: DiscoveredParty? = null
    private var lastDeviceName: String = ""
    private var reconnectAttempts = 0

    /** The party's host address, once connected — same value across reconnects within one session. */
    val hostAddress: String? get() = lastParty?.hostAddress

    fun connect(party: DiscoveredParty, deviceName: String, udpLocalPort: Int, scope: CoroutineScope) {
        leaving = false
        reconnectAttempts = 0
        lastParty = party
        lastDeviceName = deviceName
        this.udpLocalPort = udpLocalPort
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(party.hostAddress, party.port), CONNECT_TIMEOUT_MS)
                socket = s
                writer = s.getOutputStream().bufferedWriter()
                send(PartyMessage.Hello(PartyProtocol.VERSION, deviceName, udpLocalPort))
                if (readLoop(s)) handleDisconnect(scope)
            } catch (e: Exception) {
                Log.d(TAG, "Join failed: ${e.message}")
                onJoinFailed(party.serviceName)
            }
        }
    }

    /** Resets the give-up counter — called once a WELCOME actually lands, i.e. a reconnect succeeded. */
    fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }

    fun leave() {
        leaving = true
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
    }

    fun send(message: PartyMessage) {
        val w = writer ?: return
        synchronized(w) {
            w.write(message.encode())
            w.write("\n")
            w.flush()
        }
    }

    /**
     * Returns true for a normal end (EOF, BYE, or a caught I/O exception) —
     * the caller should run its disconnect/reconnect handling. Returns
     * false only for ERROR (ends the loop right where the message arrived,
     * having already told [onRejected]); the caller must NOT treat that as
     * a disconnect to recover from.
     */
    private fun readLoop(socket: Socket): Boolean {
        val reader = socket.getInputStream().bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                when (val message = PartyMessage.parse(line)) {
                    is PartyMessage.Error -> {
                        onRejected(message.reason)
                        return false
                    }
                    is PartyMessage.Bye -> break
                    null -> {}
                    else -> onMessage(message)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connection lost: ${e.message}")
        }
        return true
    }

    /** Called once per actual disconnect (not a rejection) — decides and, if warranted, schedules a reconnect. */
    private fun handleDisconnect(scope: CoroutineScope) {
        runCatching { socket?.close() }
        socket = null
        writer = null
        val party = lastParty
        val willRetry = !leaving && party != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS
        onDisconnected(willRetry)
        if (willRetry) {
            reconnectAttempts++
            scheduleReconnect(party!!, scope)
        }
    }

    /**
     * Rejoins the last host after a dropped connection. The host treats us as
     * a fresh late joiner: WELCOME, then the current song's PREPARE — the
     * cached download passes the hash check, so READY is near-instant and the
     * host's catch-up START drops us back into the running song in sync.
     */
    private fun scheduleReconnect(party: DiscoveredParty, scope: CoroutineScope) {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (leaving) return@launch
            Log.d(TAG, "Reconnect attempt $reconnectAttempts to ${party.hostAddress}")
            try {
                val s = Socket()
                s.connect(InetSocketAddress(party.hostAddress, party.port), CONNECT_TIMEOUT_MS)
                socket = s
                writer = s.getOutputStream().bufferedWriter()
                send(PartyMessage.Hello(PartyProtocol.VERSION, lastDeviceName, udpLocalPort))
                if (readLoop(s)) handleDisconnect(scope)
            } catch (e: Exception) {
                Log.d(TAG, "Reconnect failed: ${e.message}")
                handleDisconnect(scope) // schedules the next attempt or gives up
            }
        }
    }

    private companion object {
        const val TAG = "PartyGuestControlChannel"
        const val CONNECT_TIMEOUT_MS = 5_000

        /** Reconnect every few seconds for ~2 minutes before giving up. */
        const val RECONNECT_DELAY_MS = 3_000L
        const val MAX_RECONNECT_ATTEMPTS = 40
    }
}
