package com.jp.paperplayer.model.ui

import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.PartyMember

enum class PartyRole { NONE, HOST, GUEST }

enum class SyncQuality { UNKNOWN, GOOD, POOR }

/** Live sync internals surfaced on the guest's debug panel. */
data class PartySyncDebug(
    /** hostClock - guestClock from the last ping round. */
    val clockOffsetMs: Long? = null,
    val medianRttMs: Long? = null,
    /** actual - expected position at the last drift check; positive = ahead of the party. */
    val lastDriftMs: Long? = null,
    val playbackSpeed: Float = 1f,
    val expectedPositionMs: Long = 0L,
    val actualPositionMs: Long = 0L,
    val seekCorrections: Int = 0,
    val nudgeCorrections: Int = 0,
    /** Most recent drift samples, oldest first (bounded). */
    val driftHistory: List<Long> = emptyList(),
)

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
    val syncDebug: PartySyncDebug = PartySyncDebug(),
    val error: String? = null,
)
