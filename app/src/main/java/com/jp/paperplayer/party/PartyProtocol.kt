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
                "BYE" -> Bye
                else -> null
            }
        }
    }
}
