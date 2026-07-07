package com.jp.paperplayer.lyrics

import android.util.Log
import com.jp.paperplayer.model.data.LyricLine
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Reads embedded lyrics from an audio file using JAudioTagger.
 *
 * Reads the LYRICS/USLT tag (works for MP3, FLAC, OGG, M4A) and hands the
 * text to LrcParser. Most music apps that embed synced lyrics store them as
 * LRC-formatted text inside this tag.
 *
 * SYLT (ID3 binary synced-lyrics frame) support can be added once the exact
 * JAudioTagger API is verified against the target files.
 */
object LyricsExtractor {

    private const val TAG = "LyricsExtractor"

    init {
        // Silence JAudioTagger's verbose java.util.logging output on Android
        java.util.logging.Logger.getLogger("org.jaudiotagger").level =
            java.util.logging.Level.OFF
    }

    fun extract(filePath: String): List<LyricLine> {
        if (filePath.isEmpty()) return emptyList()
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return emptyList()

        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: return emptyList()

            val lyricsText = try {
                tag.getFirst(FieldKey.LYRICS)
            } catch (_: Exception) {
                ""
            }

            if (lyricsText.isNotEmpty()) LrcParser.parse(lyricsText) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read lyrics from $filePath: ${e.message}")
            emptyList()
        }
    }
}
