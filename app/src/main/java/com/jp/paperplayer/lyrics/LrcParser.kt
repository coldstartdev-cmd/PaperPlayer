package com.jp.paperplayer.lyrics

import com.jp.paperplayer.model.data.LyricLine

/**
 * Parses LRC-format text into a list of timed lyric lines.
 * Handles [mm:ss.xx], [mm:ss:xx], and [mm:ss] timestamp formats.
 */
object LrcParser {

    // Captures: minutes, seconds, optional milliseconds, and line text
    private val timeRegex = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")

    fun parse(lrc: String): List<LyricLine> {
        val timedLines = mutableListOf<LyricLine>()
        val plainLines = mutableListOf<String>()

        for (rawLine in lrc.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("[ti:") || line.startsWith("[ar:") ||
                line.startsWith("[al:") || line.startsWith("[by:") || line.startsWith("[offset:")
            ) continue

            val match = timeRegex.find(line)
            if (match == null) {
                plainLines += line
                continue
            }
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
                timedLines += LyricLine(timeMs, trimmedText)
            }
        }

        // Most embedded LYRICS tags aren't LRC-synced at all — just plain text with no
        // timestamps. Only treat this as timed LRC if at least one line actually had a
        // timestamp; otherwise every line would previously be silently dropped, making
        // the file look like it had no lyrics at all. Fall back to showing them untimed.
        return if (timedLines.isNotEmpty()) {
            timedLines.sortedBy { it.timeMs }
        } else {
            plainLines.filter { it.isNotEmpty() }.map { LyricLine(0L, it) }
        }
    }
}
