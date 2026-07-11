package com.jp.paperplayer.party

import android.content.Context
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.scanner.MusicScanner
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Song lookup and hashing for the party host: resolves a library song id to
 * a [Song] (cached after the first library scan) and computes/caches its
 * SHA-256 for the file-integrity checks guests run before every scheduled
 * start. [resolve] is called both from the host engine's own coroutines and
 * from [PartyFileServer]'s NanoHTTPD worker threads — the unsynchronized
 * cache write is a pre-existing benign race (worst case a redundant rescan,
 * both writers converge on the same map contents) kept as-is, not "fixed"
 * here.
 */
class PartyHostSongCatalog(private val context: Context) {

    private var songCache: Map<Long, Song>? = null
    private val hashCache = mutableMapOf<Long, String>()

    suspend fun resolve(songId: Long): Song? {
        songCache?.get(songId)?.let { return it }
        val scanned = MusicScanner(context).scan().associateBy { it.id }
        songCache = scanned
        return scanned[songId]
    }

    /** Cached per song; an unreadable file yields "" which disables hash checks. */
    suspend fun sha256(song: Song): String = withContext(Dispatchers.IO) {
        synchronized(hashCache) { hashCache[song.id] }?.let { return@withContext it }
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(song.uri) ?: return@withContext ""
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        synchronized(hashCache) { hashCache[song.id] = hex }
        hex
    }

    fun prepareMessage(song: Song, sha256: String): PartyMessage.Prepare {
        val sizeBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(song.uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        return PartyMessage.Prepare(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.duration,
            ext = song.filePath.substringAfterLast('.', "mp3"),
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }
}
