package com.jp.paperplayer.metadata

import android.util.Log
import com.jp.paperplayer.model.data.MusicBrainzMatch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MusicBrainzClient {

    private const val TAG = "MusicBrainzClient"
    private const val BASE = "https://musicbrainz.org/ws/2/recording"

    // TODO: swap for a real contact URL/email before leaning on this feature heavily —
    // MusicBrainz enforces its User-Agent requirement more strictly than LRCLib.
    private const val USER_AGENT = "PaperPlayer/1.0 ( https://github.com/yourusername/paperplayer )"

    /**
     * Searches MusicBrainz recordings, combining the non-blank fields into a single Lucene
     * query (e.g. `recording:"x" AND artist:"y" AND release:"z"`). Returns up to 15 matches,
     * or an empty list if all fields are blank or the request fails.
     */
    fun search(title: String, artist: String, album: String): List<MusicBrainzMatch> {
        val clauses = buildList {
            if (title.isNotBlank()) add("recording:\"${escape(title)}\"")
            if (artist.isNotBlank()) add("artist:\"${escape(artist)}\"")
            if (album.isNotBlank()) add("release:\"${escape(album)}\"")
        }
        if (clauses.isEmpty()) return emptyList()

        val query = clauses.joinToString(" AND ")
        return try {
            val url = "$BASE?query=${enc(query)}&fmt=json&limit=15"
            val body = get(url) ?: return emptyList()
            val recordings = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
            (0 until recordings.length()).mapNotNull { parseRecording(recordings.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "Search failed for \"$query\": ${e.message}")
            emptyList()
        }
    }

    private fun parseRecording(json: JSONObject): MusicBrainzMatch? {
        return try {
            val artistCredit = json.optJSONArray("artist-credit")?.optJSONObject(0)
            val artistObj = artistCredit?.optJSONObject("artist")
            val artistName = artistObj?.optString("name")?.takeIf { it.isNotBlank() }
                ?: artistCredit?.optString("name") ?: ""
            val artistId = artistObj?.optString("id")?.takeIf { it.isNotBlank() }

            val release = json.optJSONArray("releases")?.optJSONObject(0)
            val releaseId = release?.optString("id")?.takeIf { it.isNotBlank() }
            val albumName = release?.optString("title")?.takeIf { it.isNotBlank() }

            val releaseDate = optNullableString(json, "first-release-date")
                ?: release?.let { optNullableString(it, "date") }
            val year = releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4)

            val trackNumber = release?.optJSONArray("media")?.optJSONObject(0)
                ?.optJSONArray("track")?.optJSONObject(0)
                ?.let { optNullableString(it, "number") }

            MusicBrainzMatch(
                recordingId = json.getString("id"),
                artistId = artistId,
                releaseId = releaseId,
                title = json.optString("title"),
                artist = artistName,
                album = albumName,
                year = year,
                trackNumber = trackNumber,
            )
        } catch (e: Exception) {
            null
        }
    }

    // org.json's optString() returns the literal string "null" for a key whose JSON value is
    // explicit null (rather than absent) — same gotcha fixed in LrcLibClient.optNullableString.
    private fun optNullableString(json: JSONObject, key: String): String? {
        if (json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() }
    }

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 10_000
            readTimeout = 10_000
        }
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

    private fun escape(s: String) = s.replace("\"", "\\\"")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
