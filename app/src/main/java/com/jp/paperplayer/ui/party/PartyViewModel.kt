package com.jp.paperplayer.ui.party

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.jp.paperplayer.data.SettingsStore
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.ui.PartyUiState
import com.jp.paperplayer.party.PartyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thin bridge between [PartyManager] (process-wide session) and the party screen. */
class PartyViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)

    val state: StateFlow<PartyUiState> = PartyManager.state

    private val _deviceName = MutableStateFlow(
        settingsStore.getPartyDeviceName().ifBlank { Build.MODEL }
    )
    val deviceName: StateFlow<String> = _deviceName

    fun setDeviceName(name: String) {
        _deviceName.value = name
        settingsStore.setPartyDeviceName(name)
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
