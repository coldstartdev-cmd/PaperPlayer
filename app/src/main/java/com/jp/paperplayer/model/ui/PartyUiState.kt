package com.jp.paperplayer.model.ui

import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.PartyMember

enum class PartyRole { NONE, HOST, GUEST }

enum class SyncQuality { UNKNOWN, GOOD, POOR }

data class PartyUiState(
    val role: PartyRole = PartyRole.NONE,
    val partyName: String = "",
    /** Guests connected to this device's party (host role only). */
    val members: List<PartyMember> = emptyList(),
    /** Parties found on the LAN (while not in a party). */
    val discovered: List<DiscoveredParty> = emptyList(),
    val isDiscovering: Boolean = false,
    /** Host's device name once joined (guest role only). */
    val connectedHostName: String? = null,
    val isJoining: Boolean = false,
    val syncQuality: SyncQuality = SyncQuality.UNKNOWN,
    val rttMs: Long? = null,
    /** "Title — Artist" of the track the host is sharing (guest role only). */
    val nowPlaying: String? = null,
    val isDownloading: Boolean = false,
    val error: String? = null,
)
