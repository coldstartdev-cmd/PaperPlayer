package com.jp.paperplayer.lyrics

import com.jp.paperplayer.data.LyricLine

/**
 * Parses LRC-format text into a list of timed lyric lines.
 * Handles [mm:ss.xx], [mm:ss:xx], and [mm:ss] timestamp formats.
 */
object LrcParser {

    // Captures: minutes, seconds, optional milliseconds, and line text
    private val timeRegex = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")

    fun parse(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        for (rawLine in lrc.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("[ti:") || line.startsWith("[ar:") ||
                line.startsWith("[al:") || line.startsWith("[by:") || line.startsWith("[offset:")
            ) continue

            val match = timeRegex.find(line) ?: continue
            val (minutes, seconds, milliStr, text) = match.destructured

            val millis: Long = when {
                milliStr.isEmpty()      -> 0L
                milliStr.length == 1    -> milliStr.toLong() * 100L
                milliStr.length == 2    -> milliStr.toLong() * 10L
                else                    -> milliStr.toLong()
            }

            val timeMs = minutes.toLong() * 60_000L + seconds.toLong() * 1_000L + millis
            val trimmedText = text.trim()

            if (trimmedText.isNotEmpty()) {
                lines += LyricLine(timeMs, trimmedText)
            }
        }

        return lines.sortedBy { it.timeMs }
    }
}
