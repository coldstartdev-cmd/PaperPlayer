package com.jp.paperplayer.model.data

/** A party advertised on the LAN, discovered and resolved via mDNS. */
data class DiscoveredParty(
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
)

enum class PartyMemberStatus { JOINING, SYNCING, DOWNLOADING, READY, PLAYING, LOST }

/** A guest connected to the party, as seen by the host. */
data class PartyMember(
    val id: String,
    val name: String,
    val status: PartyMemberStatus,
)
