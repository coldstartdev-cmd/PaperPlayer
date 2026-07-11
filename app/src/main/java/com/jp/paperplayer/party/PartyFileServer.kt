package com.jp.paperplayer.party

import android.content.Context
import android.util.Log
import com.jp.paperplayer.model.data.Song
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

/**
 * Tiny HTTP server run by the party host, serving song bytes to guests:
 * GET /song/{id}. Bound to an ephemeral port; the actual port travels to
 * guests in the WELCOME message. Understands byte-range requests
 * (`Range: bytes=X-Y`) so a streaming guest's ExoPlayer can seek without
 * re-fetching from byte 0 — a downloading guest never sends a Range header,
 * so its plain-GET path is unaffected.
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

        val mime = mimeForExtension(song.filePath.substringAfterLast('.', ""))

        // Range requests only make sense when the length is actually known —
        // the chunked-response fallback below (unknown length) can't serve
        // partial content at all, so that stays a hard "don't attempt this" branch.
        val rangeHeader = if (length > 0) session.headers["range"] else null
        if (rangeHeader != null) {
            val range = parseRange(rangeHeader, length)
            if (range == null) {
                stream.close()
                Log.w(TAG, "Unsatisfiable range '$rangeHeader' for song $songId ($length bytes)")
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range")
                    .apply { addHeader("Content-Range", "bytes */$length") }
            }
            Log.d(TAG, "Serving song $songId range ${range.first}-${range.last}/$length")
            skipFully(stream, range.first)
            val sliceLength = range.last - range.first + 1
            return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, sliceLength).apply {
                addHeader("Content-Range", "bytes ${range.first}-${range.last}/$length")
                addHeader("Accept-Ranges", "bytes")
            }
        }

        Log.d(TAG, "Serving song $songId (${length} bytes)")
        return if (length > 0) {
            newFixedLengthResponse(Response.Status.OK, mime, stream, length)
                .apply { addHeader("Accept-Ranges", "bytes") }
        } else {
            newChunkedResponse(Response.Status.OK, mime, stream)
        }
    }

    companion object {
        private const val TAG = "PartyFileServer"
        private val SONG_PATH = Regex("^/song/(\\d+)$")
        private const val SKIP_BUFFER_SIZE = 64 * 1024

        fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

        /**
         * Parses a single-range `Range: bytes=X-Y` or `bytes=X-` request header
         * against [totalLength]. Returns null for anything malformed or
         * unsatisfiable (start at or past the end of the file) — callers
         * should respond 416 in that case. Multi-range requests
         * (`bytes=0-10,20-30`) and suffix ranges (`bytes=-500`, meaning "last
         * 500 bytes") aren't supported — ExoPlayer's HTTP data source only
         * ever sends the plain `bytes=X-Y`/`bytes=X-` forms — and are treated
         * as unsatisfiable rather than partially honored.
         */
        fun parseRange(header: String, totalLength: Long): LongRange? {
            if (!header.startsWith("bytes=")) return null
            val spec = header.removePrefix("bytes=")
            if (',' in spec) return null
            val parts = spec.split('-', limit = 2)
            if (parts.size != 2) return null
            val start = parts[0].toLongOrNull() ?: return null
            if (start < 0 || start >= totalLength) return null
            val end = parts[1].toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
            if (end < start) return null
            return start..end
        }

        /**
         * [InputStream.skip] is allowed to return 0 without being at EOF, so a
         * skip-only retry loop can stall — fall back to reading (and
         * discarding) into a scratch buffer whenever that happens.
         */
        private fun skipFully(stream: InputStream, byteCount: Long) {
            var remaining = byteCount
            val buffer = ByteArray(SKIP_BUFFER_SIZE)
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break // EOF short of the requested offset — nothing left to skip.
                remaining -= read
            }
        }
    }
}
