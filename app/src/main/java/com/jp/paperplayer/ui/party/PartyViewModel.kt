package com.jp.paperplayer.ui.party

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jp.paperplayer.data.SettingsStore
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.party.LatencyCalibrator
import com.jp.paperplayer.party.PartyEqController
import com.jp.paperplayer.party.PartyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Thin bridge between [PartyManager] (process-wide session) and the party screen. */
class PartyViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)

    val state: StateFlow<PartyUiState> = PartyManager.state

    private val _deviceName = MutableStateFlow(
        settingsStore.getPartyDeviceName().ifBlank { Build.MODEL }
    )
    val deviceName: StateFlow<String> = _deviceName

    private val _latencyTrimMs = MutableStateFlow(settingsStore.getPartyLatencyTrimMs())
    val latencyTrimMs: StateFlow<Long> = _latencyTrimMs

    fun setDeviceName(name: String) {
        _deviceName.value = name
        settingsStore.setPartyDeviceName(name)
    }

    fun setLatencyTrim(trimMs: Long) {
        _latencyTrimMs.value = trimMs
        settingsStore.setPartyLatencyTrimMs(trimMs)
        PartyManager.setLatencyTrim(trimMs)
    }

    private val _isCalibrating = MutableStateFlow(false)
    val isCalibrating: StateFlow<Boolean> = _isCalibrating

    private val _calibrationMessage = MutableStateFlow<String?>(null)
    val calibrationMessage: StateFlow<String?> = _calibrationMessage

    /** Acoustically measured ideal trim awaiting the user's decision. */
    private val _suggestedTrimMs = MutableStateFlow<Long?>(null)
    val suggestedTrimMs: StateFlow<Long?> = _suggestedTrimMs

    /**
     * Measures the ideal trim acoustically and surfaces it as a suggestion —
     * it never overwrites the current trim without the user accepting it.
     * Caller must already hold the RECORD_AUDIO permission.
     */
    fun calibrate() {
        if (_isCalibrating.value) return
        _isCalibrating.value = true
        _suggestedTrimMs.value = null
        _calibrationMessage.value = "Listening… hold the devices together"
        viewModelScope.launch {
            when (val result = PartyManager.calibrate { message -> _calibrationMessage.value = message }) {
                is LatencyCalibrator.Result.Success -> {
                    _suggestedTrimMs.value = result.trimMs
                    _calibrationMessage.value = null
                }
                is LatencyCalibrator.Result.Failure -> {
                    _calibrationMessage.value = result.reason
                }
            }
            _isCalibrating.value = false
        }
    }

    fun applySuggestedTrim() {
        _suggestedTrimMs.value?.let { setLatencyTrim(it) }
        _suggestedTrimMs.value = null
    }

    fun dismissSuggestedTrim() {
        _suggestedTrimMs.value = null
    }

    fun startHosting() {
        val name = _deviceName.value.ifBlank { Build.MODEL }
        PartyManager.startHosting(getApplication(), partyName = "$name's party", hostDeviceName = name)
    }

    fun startDiscovery() = PartyManager.startDiscovery(getApplication())

    fun stopDiscovery() = PartyManager.stopDiscovery()

    fun join(party: DiscoveredParty) {
        PartyManager.join(getApplication(), party, _deviceName.value.ifBlank { Build.MODEL })
    }

    fun leaveParty() = PartyManager.stop()

    /**
     * This device's own currently active bass/mid/treble party role —
     * whether it got there via the host's own local picker (self, if
     * hosting) or a SET_EQ_ROLE message from the host (if a guest).
     */
    val myEqRole: StateFlow<PartyEqRole> = PartyEqController.role

    /** Host only, and only for its own device: applies directly, no network round trip. */
    fun setMyEqRole(role: PartyEqRole) = PartyEqController.setRole(role)

    fun setGuestEqRole(memberId: String, role: PartyEqRole) = PartyManager.setGuestEqRole(memberId, role)
}
