package com.jp.paperplayer.lyrics

import com.jp.paperplayer.model.data.LyricLine

object LrcWriter {
    fun write(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
        val ms = line.timeMs
        val min = ms / 60_000L
        val sec = (ms % 60_000L) / 1_000L
        val cs = (ms % 1_000L) / 10L
        "[%02d:%02d.%02d]%s".format(min, sec, cs, line.text)
    }
}
