package com.jp.paperplayer.party

import android.content.Context
import android.util.Log
import com.jp.paperplayer.model.data.Song
import fi.iki.elonen.NanoHTTPD

/**
 * Tiny HTTP server run by the party host, serving song bytes to guests:
 * GET /song/{id}. Bound to an ephemeral port; the actual port travels to
 * guests in the WELCOME message.
 */
class PartyFileServer(
    private val context: Context,
    private val resolveSong: (Long) -> Song?,
) : NanoHTTPD(0) {

    override fun serve(session: IHTTPSession): Response {
        val match = SONG_PATH.find(session.uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        val songId = match.groupValues[1].toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        val song = resolveSong(songId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown song")

        // Always via the content resolver — direct file access breaks under scoped storage.
        val length = runCatching {
            context.contentResolver.openAssetFileDescriptor(song.uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        val stream = runCatching { context.contentResolver.openInputStream(song.uri) }.getOrNull()
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Unreadable")

        Log.d(TAG, "Serving song $songId (${length} bytes)")
        val mime = mimeForExtension(song.filePath.substringAfterLast('.', ""))
        return if (length > 0) {
            newFixedLengthResponse(Response.Status.OK, mime, stream, length)
        } else {
            newChunkedResponse(Response.Status.OK, mime, stream)
        }
    }

    companion object {
        private const val TAG = "PartyFileServer"
        private val SONG_PATH = Regex("^/song/(\\d+)$")

        fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
