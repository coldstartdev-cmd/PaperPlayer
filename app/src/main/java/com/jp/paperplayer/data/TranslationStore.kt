package com.jp.paperplayer.data

import android.content.Context
import com.jp.paperplayer.lyrics.LrcParser
import com.jp.paperplayer.lyrics.LrcWriter
import com.jp.paperplayer.model.data.LyricLine
import java.io.File

class TranslationStore(context: Context) {

    private val dir = File(context.filesDir, "translations")

    fun save(songId: Long, languageCode: String, lines: List<LyricLine>) {
        dir.mkdirs()
        File(dir, "${songId}_${languageCode}.lrc").writeText(LrcWriter.write(lines))
    }

    fun load(songId: Long, languageCode: String): List<LyricLine>? {
        val file = File(dir, "${songId}_${languageCode}.lrc")
        return if (file.exists()) LrcParser.parse(file.readText()) else null
    }

    fun getAvailableLanguages(songId: Long): List<String> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("${songId}_") && it.name.endsWith(".lrc") }
            ?.map { it.name.removePrefix("${songId}_").removeSuffix(".lrc") }
            ?: emptyList()
    }
}
