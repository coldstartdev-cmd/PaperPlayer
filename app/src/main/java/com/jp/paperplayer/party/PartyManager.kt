package com.jp.paperplayer.party

import android.content.Context
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.ui.PartyUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide owner of the party session. Lives outside any ViewModel so the
 * party survives navigation; at most one engine (host or guest) is active.
 */
object PartyManager {

    private val _state = MutableStateFlow(PartyUiState())
    val state: StateFlow<PartyUiState> = _state

    private var hostEngine: PartyHostEngine? = null
    private var guestEngine: PartyGuestEngine? = null

    fun startHosting(context: Context, partyName: String, hostDeviceName: String) {
        stop()
        hostEngine = PartyHostEngine(context.applicationContext, ::updateState).also {
            it.start(partyName, hostDeviceName)
        }
    }

    fun startDiscovery(context: Context) {
        if (hostEngine != null) return
        val engine = guestEngine ?: PartyGuestEngine(context.applicationContext, ::updateState)
            .also { guestEngine = it }
        engine.startDiscovery()
    }

    fun stopDiscovery() {
        guestEngine?.stopDiscovery()
    }

    fun join(context: Context, party: DiscoveredParty, deviceName: String) {
        val engine = guestEngine ?: PartyGuestEngine(context.applicationContext, ::updateState)
            .also { guestEngine = it }
        engine.join(party, deviceName)
    }

    /** Applies a new audio-latency trim to the active guest session, if any. */
    fun setLatencyTrim(trimMs: Long) {
        guestEngine?.latencyTrimMs = trimMs
    }

    /** Runs acoustic latency calibration on the active guest session. */
    suspend fun calibrate(): LatencyCalibrator.Result =
        guestEngine?.calibrate() ?: LatencyCalibrator.Result.Failure("Not in a party")

    /** Ends hosting or leaves as guest; resets to the idle state. */
    fun stop() {
        hostEngine?.stop()
        hostEngine = null
        guestEngine?.leave()
        guestEngine = null
        _state.value = PartyUiState()
    }

    private fun updateState(transform: (PartyUiState) -> PartyUiState) {
        _state.value = transform(_state.value)
    }
}
