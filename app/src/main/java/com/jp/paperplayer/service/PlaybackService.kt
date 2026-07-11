package com.jp.paperplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.Equalizer
import android.util.Log
import com.jp.paperplayer.MainActivity
import com.jp.paperplayer.party.PartyEqController
import com.jp.paperplayer.party.applyPartyEqRole
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var equalizer: Equalizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Party mode's bass/mid/treble "distributed speaker" effect: a platform
    // Equalizer on this player's own audio session, driven by whatever role
    // PartyEqController currently holds. ExoPlayer doesn't finalize a real
    // audio session id until the first AudioTrack is actually configured
    // (i.e. once playback of a track with audio begins) — attaching in
    // onCreate() would attach to session 0 (unset), silently doing nothing.
    // This listener (re)attaches whenever the real session id shows up.
    private val eqListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            attachEqualizer(audioSessionId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .build()

        player.addListener(eqListener)
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            attachEqualizer(player.audioSessionId)
        }
        scope.launch {
            PartyEqController.role.collect { role ->
                equalizer?.let { eq ->
                    runCatching { applyPartyEqRole(eq, role) }
                        .onFailure { Log.w(TAG, "Failed to apply EQ role $role: ${it.message}") }
                }
            }
        }
    }

    private fun attachEqualizer(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        equalizer?.release()
        equalizer = runCatching { Equalizer(0, sessionId) }
            .onFailure { Log.w(TAG, "Equalizer unavailable: ${it.message}") }
            .getOrNull()
            ?.also { eq -> runCatching { applyPartyEqRole(eq, PartyEqController.role.value) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

    override fun onDestroy() {
        player.removeListener(eqListener)
        equalizer?.release()
        scope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
