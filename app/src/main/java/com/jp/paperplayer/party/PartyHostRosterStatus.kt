package com.jp.paperplayer.party

import com.jp.paperplayer.model.data.PartyEqRole
import com.jp.paperplayer.model.data.PartyMemberStatus

/**
 * Per-guest bookkeeping for the host's roster: connection status
 * (JOINING/SYNCING/DOWNLOADING/READY/PLAYING) and the bass/mid/treble
 * "distributed speaker" EQ role assignment. Pure map bookkeeping — the host
 * engine still owns publishing the roster to UI state and sending
 * SET_EQ_ROLE to the affected client, since those need [publishMembers]'s
 * view across every collaborator (clients, sync stats) at once.
 */
class PartyHostRosterStatus {
    private val statuses = mutableMapOf<String, PartyMemberStatus>()
    private val eqRoles = mutableMapOf<String, PartyEqRole>()

    fun setStatus(memberId: String, status: PartyMemberStatus) {
        synchronized(statuses) { statuses[memberId] = status }
    }

    fun setAllStatuses(status: PartyMemberStatus) {
        synchronized(statuses) { statuses.keys.forEach { statuses[it] = status } }
    }

    fun status(memberId: String): PartyMemberStatus =
        synchronized(statuses) { statuses[memberId] ?: PartyMemberStatus.JOINING }

    fun setEqRole(memberId: String, role: PartyEqRole) {
        synchronized(eqRoles) { eqRoles[memberId] = role }
    }

    fun eqRole(memberId: String): PartyEqRole =
        synchronized(eqRoles) { eqRoles[memberId] } ?: PartyEqRole.NONE

    fun remove(memberId: String) {
        synchronized(statuses) { statuses.remove(memberId) }
        synchronized(eqRoles) { eqRoles.remove(memberId) }
    }

    fun clear() {
        synchronized(statuses) { statuses.clear() }
        synchronized(eqRoles) { eqRoles.clear() }
    }
}
