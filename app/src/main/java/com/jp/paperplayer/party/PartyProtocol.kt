package com.jp.paperplayer.party

import org.json.JSONObject

/**
 * Wire protocol for party mode: one JSON object per line (UTF-8, '\n'-terminated)
 * over a plain TCP socket. Every message carries a "type"; unknown types are
 * ignored by both sides so newer devices can talk to older ones.
 */
object PartyProtocol {
    const val VERSION = 1

    /** mDNS service type advertised by hosts and searched by guests. */
    const val SERVICE_TYPE = "_paperplayer._tcp."
}

sealed class PartyMessage {

    abstract fun toJson(): JSONObject

    fun encode(): String = toJson().toString()

    /** Guest -> host, first message after connecting. */
    data class Hello(val protocolVersion: Int, val deviceName: String) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "HELLO")
            .put("protocolVersion", protocolVersion)
            .put("deviceName", deviceName)
    }

    /** Host -> guest, accepts the guest into the party. */
    data class Welcome(
        val protocolVersion: Int,
        val partyName: String,
        val hostName: String,
        val httpPort: Int,
        val memberId: String,
    ) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "WELCOME")
            .put("protocolVersion", protocolVersion)
            .put("partyName", partyName)
            .put("hostName", hostName)
            .put("httpPort", httpPort)
            .put("memberId", memberId)
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

    /** Guest -> host: please emit a calibration chirp so I can measure latency. */
    data object CalibrateRequest : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject().put("type", "CALIBRATE_REQUEST")
    }

    /** Host -> guest: the host will emit its chirp at host time [atHostElapsedMs]. */
    data class CalibrateChirp(val atHostElapsedMs: Long) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "CALIBRATE_CHIRP")
            .put("atHostElapsedMs", atHostElapsedMs)
    }

    /** Host -> guest: calibration can't run right now. */
    data class CalibrateDenied(val reason: String) : PartyMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "CALIBRATE_DENIED")
            .put("reason", reason)
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
                )
                "WELCOME" -> Welcome(
                    protocolVersion = json.optInt("protocolVersion", -1),
                    partyName = json.optString("partyName"),
                    hostName = json.optString("hostName"),
                    httpPort = json.optInt("httpPort", 0),
                    memberId = json.optString("memberId"),
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
                )
                "READY" -> Ready(json.optLong("songId"))
                "START" -> Start(
                    songId = json.optLong("songId"),
                    positionMs = json.optLong("positionMs"),
                    atHostElapsedMs = json.optLong("atHostElapsedMs"),
                )
                "PAUSE" -> Pause(json.optLong("positionMs"))
                "RESUME" -> Resume(json.optLong("positionMs"), json.optLong("atHostElapsedMs"))
                "CALIBRATE_REQUEST" -> CalibrateRequest
                "CALIBRATE_CHIRP" -> CalibrateChirp(json.optLong("atHostElapsedMs"))
                "CALIBRATE_DENIED" -> CalibrateDenied(json.optString("reason", "Calibration unavailable"))
                "BYE" -> Bye
                else -> null
            }
        }
    }
}
