package com.jp.paperplayer.ui.party

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jp.paperplayer.data.SettingsStore
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.party.LatencyCalibrator
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

    /** Caller must already hold the RECORD_AUDIO permission. */
    fun calibrate() {
        if (_isCalibrating.value) return
        _isCalibrating.value = true
        _calibrationMessage.value = "Listening… hold the devices together"
        viewModelScope.launch {
            when (val result = PartyManager.calibrate()) {
                is LatencyCalibrator.Result.Success -> {
                    setLatencyTrim(result.trimMs)
                    _calibrationMessage.value = "Calibrated: ${if (result.trimMs > 0) "+" else ""}${result.trimMs} ms"
                }
                is LatencyCalibrator.Result.Failure -> {
                    _calibrationMessage.value = result.reason
                }
            }
            _isCalibrating.value = false
        }
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
}
