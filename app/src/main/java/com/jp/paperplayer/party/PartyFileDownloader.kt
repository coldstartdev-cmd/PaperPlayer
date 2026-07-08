package com.jp.paperplayer.party

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads party songs from the host into the app cache. Files land in
 * cacheDir/party/ and are deleted when the guest leaves; a .part file is
 * renamed into place only after a complete download so a torn transfer is
 * never mistaken for a finished song.
 */
class PartyFileDownloader(context: Context) {

    private val partyDir = File(context.cacheDir, "party")

    /** Returns the local file, downloading it unless a complete copy already exists. */
    fun download(url: String, songId: Long, ext: String, expectedSizeBytes: Long): File {
        partyDir.mkdirs()
        val target = File(partyDir, "$songId.$ext")
        if (target.exists() && (expectedSizeBytes <= 0 || target.length() == expectedSizeBytes)) {
            Log.d(TAG, "Reusing cached ${target.name}")
            return target
        }

        val part = File(partyDir, "$songId.$ext.part")
        part.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            connection.inputStream.use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }

        if (expectedSizeBytes > 0 && part.length() != expectedSizeBytes) {
            part.delete()
            throw IllegalStateException("Incomplete download: ${part.length()} of $expectedSizeBytes bytes")
        }
        target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Could not finalize ${target.name}")
        Log.d(TAG, "Downloaded ${target.name} (${target.length()} bytes)")
        return target
    }

    /** Removes all downloaded party files (leaving the party, or purging stale files on join). */
    fun cleanup() {
        partyDir.deleteRecursively()
    }

    private companion object {
        const val TAG = "PartyFileDownloader"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
