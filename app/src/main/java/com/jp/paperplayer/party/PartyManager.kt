package com.jp.paperplayer.party

import android.content.Context
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.ui.PartyUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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

    /** Host only: assigns a guest's bass/mid/treble party-trick role. */
    fun setGuestEqRole(memberId: String, role: PartyEqRole) {
        hostEngine?.setGuestEqRole(memberId, role)
    }

    /** Runs acoustic latency calibration on the active guest session. */
    suspend fun calibrate(onProgress: (String) -> Unit = {}): LatencyCalibrator.Result =
        guestEngine?.calibrate(onProgress) ?: LatencyCalibrator.Result.Failure("Not in a party")

    /** Ends hosting or leaves as guest; resets to the idle state. */
    fun stop() {
        hostEngine?.stop()
        hostEngine = null
        guestEngine?.leave()
        guestEngine = null
        _state.value = PartyUiState()
    }

    // The host engine calls this from several independent threads at once —
    // the Main-thread player listener, the IO-dispatched UDP receive loop,
    // per-client TCP handler threads — all firing roughly every 500ms per
    // guest. A plain "_state.value = transform(_state.value)" read-modify-
    // write is not atomic: two threads can both read the same old value,
    // compute independently, and the second write silently clobbers the
    // first's changes (e.g. a member's fresh sync stats overwritten by a
    // status update that started just before it, based on stale state).
    // MutableStateFlow.update runs a compare-and-set loop instead, so a
    // losing writer retries against the winner's result rather than
    // stomping on it.
    private fun updateState(transform: (PartyUiState) -> PartyUiState) {
        _state.update(transform)
    }
}
