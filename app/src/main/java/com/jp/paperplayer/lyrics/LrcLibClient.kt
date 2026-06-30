package com.jp.paperplayer.lyrics

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object LrcLibClient {

    private const val TAG = "LrcLibClient"
    private const val BASE = "https://lrclib.net/api"

    data class Track(
        val id: Int,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val duration: Int,          // seconds
        val syncedLyrics: String?,
        val plainLyrics: String?,
    )

    // ── Search ────────────────────────────────────────────────────────────────

    fun search(query: String): List<Track> {
        return try {
            val url = "$BASE/search?q=${enc(query)}"
            val body = get(url) ?: return emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { parseTrack(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "Search failed for \"$query\": ${e.message}")
            emptyList()
        }
    }

    // ── Publish ───────────────────────────────────────────────────────────────

    /**
     * Publishes synced lyrics to LRCLib.
     * Performs LRCLib's SHA-256 proof-of-work challenge before posting.
     */
    fun publish(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int,
        plainLyrics: String,
        syncedLyrics: String,
    ): Boolean {
        return try {
            // 1. Request challenge — POST with empty JSON body and Content-Type required.
            val challengeConn = openConn("$BASE/request-challenge", "POST")
            challengeConn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            challengeConn.doOutput = true
            challengeConn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val challengeCode = challengeConn.responseCode
            Log.d(TAG, "[$trackName] Challenge response: $challengeCode")
            if (challengeCode != 200) {
                challengeConn.disconnect()
                Log.w(TAG, "[$trackName] Challenge request failed with $challengeCode")
                return false
            }
            val challengeBody = BufferedReader(InputStreamReader(challengeConn.inputStream)).use { it.readText() }
            challengeConn.disconnect()
            val challenge = JSONObject(challengeBody)
            val prefix = challenge.getString("prefix")
            val target = challenge.getString("target").lowercase()
            Log.d(TAG, "[$trackName] Challenge obtained — prefix=${prefix.take(8)}… target=${target.take(8)}…")

            // 2. Solve proof-of-work: find nonce where hex(SHA256(prefix+nonce)) < target
            val t0 = System.currentTimeMillis()
            val nonce = solveChallenge(prefix, target)
            if (nonce == null) {
                Log.w(TAG, "[$trackName] Challenge solve timed out after 60 s")
                return false
            }
            Log.d(TAG, "[$trackName] Nonce=$nonce solved in ${System.currentTimeMillis() - t0} ms")

            // 3. Post lyrics
            val body = JSONObject().apply {
                put("trackName", trackName)
                put("artistName", artistName)
                put("albumName", albumName)
                put("duration", durationSec)
                put("plainLyrics", plainLyrics)
                put("syncedLyrics", syncedLyrics)
            }.toString()

            val conn = openConn("$BASE/publish", "POST")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Publish-Token", "$prefix:$nonce")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            Log.d(TAG, "[$trackName] Publish response: $code")
            conn.disconnect()
            code == 201
        } catch (e: Exception) {
            Log.w(TAG, "Publish failed for \"$trackName\"", e)
            false
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun get(url: String): String? {
        val conn = openConn(url, "GET")
        val code = conn.responseCode
        if (code != 200) {
            Log.d(TAG, "GET $url → $code")
            conn.disconnect()
            return null
        }
        val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()
        return body
    }

    private fun post(url: String, body: String): String? {
        val conn = openConn(url, "POST")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code != 200 && code != 201) {
            Log.d(TAG, "POST $url → $code")
            conn.disconnect()
            return null
        }
        val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()
        return response
    }

    private fun openConn(url: String, method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("User-Agent", "PaperPlayer/1.0")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
    }

    private fun parseTrack(json: JSONObject): Track? {
        return try {
            Track(
                id           = json.getInt("id"),
                trackName    = json.optString("trackName"),
                artistName   = json.optString("artistName"),
                albumName    = json.optString("albumName"),
                duration     = json.optDouble("duration", 0.0).toInt(),
                syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() },
                plainLyrics  = json.optString("plainLyrics").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds a nonce such that SHA256(prefix + nonce) < target numerically.
     * Compares raw bytes to avoid sign-extension bugs with Kotlin's signed Byte type.
     * Times out after 30 s.
     */
    private fun solveChallenge(prefix: String, target: String): String? {
        // Parse the 64-char hex target into 32 bytes once, upfront.
        val targetBytes = ByteArray(32) { i ->
            target.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val md = MessageDigest.getInstance("SHA-256")
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
        var nonce = 0L
        val deadline = System.currentTimeMillis() + 60_000L

        while (true) {
            // Check the deadline every 50 000 iterations to minimise overhead.
            if (nonce % 50_000L == 0L && System.currentTimeMillis() >= deadline) return null
            val nonceStr = nonce.toString()
            md.reset()
            md.update(prefixBytes)
            val hash = md.digest(nonceStr.toByteArray(Charsets.UTF_8))
            if (hashLessThan(hash, targetBytes)) return nonceStr
            nonce++
        }
    }

    private fun hashLessThan(hash: ByteArray, target: ByteArray): Boolean {
        for (i in hash.indices) {
            val h = hash[i].toInt() and 0xFF
            val t = target[i].toInt() and 0xFF
            if (h < t) return true
            if (h > t) return false
        }
        return false  // equal → not less than
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
