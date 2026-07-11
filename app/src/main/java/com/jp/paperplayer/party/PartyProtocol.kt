package com.jp.paperplayer.party

import com.jp.paperplayer.model.data.PartyEqRole
import org.json.JSONObject

/**
 * Wire protocol for party mode. Most messages are one JSON object per line
 * (UTF-8, '\n'-terminated) over a plain TCP control socket. HOST_SYNC and
 * SYNC_STATS — the two tick-every-500ms, "a missed one doesn't matter since
 * the next supersedes it" messages — travel as individual UDP datagrams
 * instead (see HELLO/WELCOME's udpPort): a dropped one just gets skipped,
 * rather than head-of-line-blocking whatever else was queued behind it on
 * the TCP stream. Every message carries a "type"; unknown types are ignored
 * by both sides so newer devices can talk to older ones.
 */
object PartyProtocol {
    const val VERSION = 7

    /** mDNS service type advertised by hosts and searched by guests. */
    const val SERVICE_TYPE = "_paperplayer._tcp."
}

sealed class PartyMessage {

    abstract fun toJson(): JSONObject

    fun encode(): String = toJson().toString()

    /** Guest -> host, first message after connecting. */
    data class Hello(val protocolVersion: Int, val deviceName: String, val udpPort: Int) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "HELLO")
            .put("protocolVersion", protocolVersion)
            .put("deviceName", deviceName)
            .put("udpPort", udpPort)
    }

    /** Host -> guest, accepts the guest into the party. */
    data class Welcome(
        val protocolVersion: Int,
        val partyName: String,
        val hostName: String,
        val httpPort: Int,
        val memberId: String,
        /** Where the host's UDP socket listens for this guest's SYNC_STATS, and sends HOST_SYNC from. */
        val udpPort: Int,
    ) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "WELCOME")
            .put("protocolVersion", protocolVersion)
            .put("partyName", partyName)
            .put("hostName", hostName)
            .put("httpPort", httpPort)
            .put("memberId", memberId)
            .put("udpPort", udpPort)
    }

    /** Host -> guest, join rejected; the socket closes after this. */
    data class Error(val reason: String) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "ERROR")
            .put("reason", reason)
    }

    /** Guest -> host. [t0] is the guest's elapsedRealtime at send. */
    data class Ping(val seq: Int, val t0: Long) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "PING")
            .put("seq", seq)
            .put("t0", t0)
    }

    /** Host -> guest. Echoes [t0]; [t1] is the host's elapsedRealtime at receipt. */
    data class Pong(val seq: Int, val t0: Long, val t1: Long) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "PONG")
            .put("seq", seq)
            .put("t0", t0)
            .put("t1", t1)
    }

    /**
     * Host -> guest: download this song and prepare it for playback. The guest
     * builds the download URL from the resolved host address plus the httpPort
     * from WELCOME: http://host:port/song/{songId}.
     */
    data class Prepare(
        val songId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val ext: String,
        val sizeBytes: Long,
        val sha256: String,
    ) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "PREPARE")
            .put("songId", songId)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("durationMs", durationMs)
            .put("ext", ext)
            .put("sizeBytes", sizeBytes)
            .put("sha256", sha256)
    }

    /**
     * Host -> guest: playback of [songId] is about to start at [positionMs].
     * The guest re-verifies its file against [sha256], re-measures the clock
     * offset, parks its player at the position, and answers with SYNC_READY.
     */
    data class SyncCheck(val songId: Long, val sha256: String, val positionMs: Long) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "SYNC_CHECK")
            .put("songId", songId)
            .put("sha256", sha256)
            .put("positionMs", positionMs)
    }

    /** Guest -> host: pre-start checklist result; [ok] false asks for the file again. */
    data class SyncReady(val songId: Long, val ok: Boolean, val reason: String) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "SYNC_READY")
            .put("songId", songId)
            .put("ok", ok)
            .put("reason", reason)
    }

    /**
     * Guest -> host: periodic sync telemetry for the host's dashboard — the
     * same raw numbers driving the guest's own on-device debug panel, not a
     * pre-collapsed drift figure, so the host can see exactly what each
     * guest observed and when. [driftMs]/[clockOffsetMs] are derivable
     * ([actualPositionMs] - [expectedPositionMs], [hostAtMs] - [deviceAtMs])
     * and computed host-side instead of duplicated on the wire.
     */
    data class SyncStats(
        /** This guest's own elapsedRealtime when the sample was taken. */
        val deviceAtMs: Long,
        /** [deviceAtMs] converted to the host's clock via this guest's measured offset. */
        val hostAtMs: Long,
        /** Where the party timeline says playback should be at [deviceAtMs]. */
        val expectedPositionMs: Long,
        /** Where this guest's player actually is at [deviceAtMs]. */
        val actualPositionMs: Long,
        val rttMs: Long,
        val playbackSpeed: Float,
        val seekCorrections: Int,
        val nudgeCorrections: Int,
        val latencyTrimMs: Long,
    ) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "SYNC_STATS")
            .put("deviceAtMs", deviceAtMs)
            .put("hostAtMs", hostAtMs)
            .put("expectedPositionMs", expectedPositionMs)
            .put("actualPositionMs", actualPositionMs)
            .put("rttMs", rttMs)
            .put("playbackSpeed", playbackSpeed.toDouble())
            .put("seekCorrections", seekCorrections)
            .put("nudgeCorrections", nudgeCorrections)
            .put("latencyTrimMs", latencyTrimMs)
    }

    /**
     * Host -> guest: periodic broadcast of the host's own live playback
     * position, sent every drift tick. Guests chase this directly instead of
     * an idealized start-position + elapsed-time formula.
     */
    data class HostSync(
        val atHostElapsedMs: Long,
        val positionMs: Long,
    ) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "HOST_SYNC")
            .put("atHostElapsedMs", atHostElapsedMs)
            .put("positionMs", positionMs)
    }

    /** Guest -> host: song downloaded and the player is prepared, paused at zero. */
    data class Ready(val songId: Long) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "READY")
            .put("songId", songId)
    }

    /** Host -> guest: begin playback of [songId] at [positionMs] at host time [atHostElapsedMs]. */
    data class Start(val songId: Long, val positionMs: Long, val atHostElapsedMs: Long) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "START")
            .put("songId", songId)
            .put("positionMs", positionMs)
            .put("atHostElapsedMs", atHostElapsedMs)
    }

    /** Host -> guest: pause immediately and snap to [positionMs]. */
    data class Pause(val positionMs: Long) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "PAUSE")
            .put("positionMs", positionMs)
    }

    /** Host -> guest: resume (or re-position after a seek) at host time [atHostElapsedMs]. */
    data class Resume(val positionMs: Long, val atHostElapsedMs: Long) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "RESUME")
            .put("positionMs", positionMs)
            .put("atHostElapsedMs", atHostElapsedMs)
    }

    /**
     * Guest -> host: please emit a calibration chirp in frequency [bandIndex]
     * (an index into [Chirp.BANDS]) so I can measure latency. A full
     * calibration run sends one of these per band.
     */
    data class CalibrateRequest(val bandIndex: Int) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "CALIBRATE_REQUEST")
            .put("bandIndex", bandIndex)
    }

    /** Host -> guest: the host will emit its [bandIndex] chirp at host time [atHostElapsedMs]. */
    data class CalibrateChirp(val atHostElapsedMs: Long, val bandIndex: Int) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "CALIBRATE_CHIRP")
            .put("atHostElapsedMs", atHostElapsedMs)
            .put("bandIndex", bandIndex)
    }

    /**
     * Guest -> host: this [bandIndex] round (chirp + recording) is finished —
     * safe to accept the next request. Sent whether the measurement itself
     * succeeded or not; a denied request never triggers a round, so no ack
     * follows one. Lets the host release its busy lock on an actual signal
     * instead of guessing how long the guest's chirp-and-record takes.
     */
    data class CalibrateComplete(val bandIndex: Int) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "CALIBRATE_COMPLETE")
            .put("bandIndex", bandIndex)
    }

    /** Host -> guest: calibration can't run right now. */
    data class CalibrateDenied(val reason: String) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "CALIBRATE_DENIED")
            .put("reason", reason)
    }

    /**
     * Host -> guest: adopt this frequency-emphasis role for the
     * "distributed speaker" party trick — see [PartyEqRole].
     */
    data class SetEqRole(val role: PartyEqRole) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "SET_EQ_ROLE")
            .put("role", role.name)
    }

    /** Either direction: graceful leave / party end. */
    data object Bye : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject().put("type", "BYE")
    }

    companion object {
        /** Returns null for malformed lines or unknown message types. */
        fun parse(line: String): PartyMessage? {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
            return when (json.optString("type")) {
                "HELLO" -> Hello(
                    protocolVersion = json.optInt("protocolVersion", -1),
                    deviceName = json.optString("deviceName", "Unknown device"),
                    udpPort = json.optInt("udpPort", 0),
                )
                "WELCOME" -> Welcome(
                    protocolVersion = json.optInt("protocolVersion", -1),
                    partyName = json.optString("partyName"),
                    hostName = json.optString("hostName"),
                    httpPort = json.optInt("httpPort", 0),
                    memberId = json.optString("memberId"),
                    udpPort = json.optInt("udpPort", 0),
                )
                "ERROR" -> Error(json.optString("reason", "Unknown error"))
                "PING" -> Ping(json.optInt("seq"), json.optLong("t0"))
                "PONG" -> Pong(json.optInt("seq"), json.optLong("t0"), json.optLong("t1"))
                "PREPARE" -> Prepare(
                    songId = json.optLong("songId"),
                    title = json.optString("title"),
                    artist = json.optString("artist"),
                    album = json.optString("album"),
                    durationMs = json.optLong("durationMs"),
                    ext = json.optString("ext", "mp3"),
                    sizeBytes = json.optLong("sizeBytes", -1L),
                    sha256 = json.optString("sha256", ""),
                )
                "READY" -> Ready(json.optLong("songId"))
                "SYNC_CHECK" -> SyncCheck(
                    songId = json.optLong("songId"),
                    sha256 = json.optString("sha256", ""),
                    positionMs = json.optLong("positionMs"),
                )
                "SYNC_READY" -> SyncReady(
                    songId = json.optLong("songId"),
                    ok = json.optBoolean("ok", false),
                    reason = json.optString("reason", ""),
                )
                "SYNC_STATS" -> SyncStats(
                    deviceAtMs = json.optLong("deviceAtMs"),
                    hostAtMs = json.optLong("hostAtMs"),
                    expectedPositionMs = json.optLong("expectedPositionMs"),
                    actualPositionMs = json.optLong("actualPositionMs"),
                    rttMs = json.optLong("rttMs"),
                    playbackSpeed = json.optDouble("playbackSpeed", 1.0).toFloat(),
                    seekCorrections = json.optInt("seekCorrections"),
                    nudgeCorrections = json.optInt("nudgeCorrections"),
                    latencyTrimMs = json.optLong("latencyTrimMs"),
                )
                "HOST_SYNC" -> HostSync(
                    atHostElapsedMs = json.optLong("atHostElapsedMs"),
                    positionMs = json.optLong("positionMs"),
                )
                "START" -> Start(
                    songId = json.optLong("songId"),
                    positionMs = json.optLong("positionMs"),
                    atHostElapsedMs = json.optLong("atHostElapsedMs"),
                )
                "PAUSE" -> Pause(json.optLong("positionMs"))
                "RESUME" -> Resume(json.optLong("positionMs"), json.optLong("atHostElapsedMs"))
                "CALIBRATE_REQUEST" -> CalibrateRequest(json.optInt("bandIndex", 0))
                "CALIBRATE_CHIRP" -> CalibrateChirp(json.optLong("atHostElapsedMs"), json.optInt("bandIndex", 0))
                "CALIBRATE_COMPLETE" -> CalibrateComplete(json.optInt("bandIndex", 0))
                "CALIBRATE_DENIED" -> CalibrateDenied(json.optString("reason", "Calibration unavailable"))
                "SET_EQ_ROLE" -> SetEqRole(
                    role = runCatching { PartyEqRole.valueOf(json.optString("role")) }.getOrDefault(PartyEqRole.NONE)
                )
                "BYE" -> Bye
                else -> null
            }
        }
    }
}
