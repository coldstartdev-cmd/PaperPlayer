package com.jp.paperplayer.party

import android.media.audiofx.Equalizer
import com.jp.paperplayer.model.data.PartyEqRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide bridge from "whatever assigns this device's party EQ role"
 * (the host's own local picker, or a SET_EQ_ROLE message received from the
 * host over the network) to [com.jp.paperplayer.service.PlaybackService],
 * the only thing holding the actual ExoPlayer/audio session. Neither
 * PartyHostEngine nor PartyGuestEngine has a reference to the service, so —
 * mirroring [PartyManager]'s own pattern — this is a small singleton rather
 * than plumbing a reference through several layers for one flag.
 */
object PartyEqController {
    private val _role = MutableStateFlow(PartyEqRole.NONE)
    val role: StateFlow<PartyEqRole> = _role

    fun setRole(role: PartyEqRole) {
        _role.value = role
    }
}

/**
 * The "distributed speaker" party trick: splits the platform Equalizer's
 * bands into low/mid/high thirds by center frequency, boosts the band
 * matching [role] to the effect's ceiling, and cuts the rest — so three
 * phones set to Bass/Mid/Treble in the same room each lean into a
 * different part of the mix instead of all reproducing it identically.
 * [PartyEqRole.NONE] just disables the effect (flat, no processing
 * overhead).
 */
fun applyPartyEqRole(equalizer: Equalizer, role: PartyEqRole) {
    if (role == PartyEqRole.NONE) {
        equalizer.setEnabled(false)
        return
    }
    val range = equalizer.bandLevelRange
    val boost = range[1]
    val cut = (range[0] * 0.7).toInt().toShort()
    for (band in 0 until equalizer.numberOfBands.toInt()) {
        val bandIndex = band.toShort()
        val hz = equalizer.getCenterFreq(bandIndex) / 1000
        val bandRole = when {
            hz < BASS_CEILING_HZ -> PartyEqRole.BASS
            hz > TREBLE_FLOOR_HZ -> PartyEqRole.TREBLE
            else -> PartyEqRole.MID
        }
        equalizer.setBandLevel(bandIndex, if (bandRole == role) boost else cut)
    }
    equalizer.setEnabled(true)
}

private const val BASS_CEILING_HZ = 250
private const val TREBLE_FLOOR_HZ = 4_000
