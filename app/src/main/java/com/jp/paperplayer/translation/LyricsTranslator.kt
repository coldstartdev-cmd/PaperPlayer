package com.jp.paperplayer.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.jp.paperplayer.data.LyricLine
import kotlinx.coroutines.tasks.await

suspend fun translateLyrics(
    lines: List<LyricLine>,
    targetLanguage: String,
    sourceLanguage: String = TranslateLanguage.ENGLISH,
): List<LyricLine> {
    val options = TranslatorOptions.Builder()
        .setSourceLanguage(sourceLanguage)
        .setTargetLanguage(targetLanguage)
        .build()
    val translator = Translation.getClient(options)
    return try {
        translator.downloadModelIfNeeded().await()
        lines.map { line -> line.copy(text = translator.translate(line.text).await()) }
    } finally {
        translator.close()
    }
}
