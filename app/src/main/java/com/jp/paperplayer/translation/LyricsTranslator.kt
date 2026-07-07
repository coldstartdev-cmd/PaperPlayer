package com.jp.paperplayer.translation

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.jp.paperplayer.model.data.LyricLine
import kotlinx.coroutines.tasks.await

private suspend fun detectSourceLanguage(lines: List<LyricLine>): String {
    val sample = lines.take(8).joinToString(" ") { it.text }
    val identifier = LanguageIdentification.getClient()
    return try {
        val tag = identifier.identifyLanguage(sample).await()
        if (tag == "und") TranslateLanguage.ENGLISH
        else TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
    } finally {
        identifier.close()
    }
}

suspend fun translateLyrics(
    lines: List<LyricLine>,
    targetLanguage: String,
): List<LyricLine> {
    val sourceLanguage = detectSourceLanguage(lines)
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
