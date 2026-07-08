package com.jp.paperplayer.party

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.ui.PartyRole
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.model.ui.SyncQuality
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Joins a party as a guest: discovers hosts via mDNS, connects to the control
 * socket, and estimates the clock offset to the host via ping rounds. Playback
 * arrives in later phases; [clockOffsetMs] is the bridge they will use.
 */
class PartyGuestEngine(
    context: Context,
    private val update: ((PartyUiState) -> PartyUiState) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd = NsdHelper(context)

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    /** hostClock - guestClock; null until the first ping round completes. */
    @Volatile
    var clockOffsetMs: Long? = null
        private set

    private var pingSeq = 0
    private val pendingSamples = mutableListOf<PingSample>()

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
        update { it.copy(isJoining = true, error = null) }
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(party.hostAddress, party.port), CONNECT_TIMEOUT_MS)
                socket = s
                writer = s.getOutputStream().bufferedWriter()
                send(PartyMessage.Hello(PartyProtocol.VERSION, deviceName))
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
        runCatching { send(PartyMessage.Bye) }
        runCatching { socket?.close() }
        socket = null
        writer = null
        nsd.stopDiscovery()
        scope.cancel()
        update { PartyUiState() }
    }

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
                    is PartyMessage.Pong -> onPong(message)
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
        scope.launch { pingRounds() }
    }

    private suspend fun pingRounds() {
        runPingRound(ClockSync.JOIN_PING_COUNT)
        while (true) {
            delay(OFFSET_REFRESH_INTERVAL_MS)
            runPingRound(ClockSync.REFRESH_PING_COUNT)
        }
    }

    private suspend fun runPingRound(count: Int) {
        synchronized(pendingSamples) { pendingSamples.clear() }
        repeat(count) {
            runCatching { send(PartyMessage.Ping(++pingSeq, SystemClock.elapsedRealtime())) }
            delay(ClockSync.PING_INTERVAL_MS)
        }
        // Grace period for the last replies to arrive.
        delay(500L)
        val samples = synchronized(pendingSamples) { pendingSamples.toList() }
        if (samples.isEmpty()) return
        clockOffsetMs = ClockSync.estimateOffsetMs(samples)
        val quality = if (ClockSync.isQualityPoor(samples)) SyncQuality.POOR else SyncQuality.GOOD
        update { it.copy(syncQuality = quality, rttMs = ClockSync.medianRttMs(samples)) }
    }

    private fun onPong(pong: PartyMessage.Pong) {
        val sample = PingSample(t0 = pong.t0, t1 = pong.t1, t2 = SystemClock.elapsedRealtime())
        synchronized(pendingSamples) { pendingSamples.add(sample) }
    }

    private fun onDisconnected() {
        runCatching { socket?.close() }
        socket = null
        writer = null
        update {
            if (it.role == PartyRole.GUEST) {
                it.copy(error = "Lost connection to the party")
            } else {
                it.copy(isJoining = false)
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
        const val OFFSET_REFRESH_INTERVAL_MS = 30_000L
    }
}
