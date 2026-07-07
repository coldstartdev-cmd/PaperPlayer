package com.jp.paperplayer.translation

import android.os.Build

/**
 * Converts native-script lyric text (Telugu, Devanagari, Tamil, etc.) into readable Latin
 * script using Android's built-in ICU transliterator — for reading/singing along without
 * knowing the native script. This is script conversion, not translation: meaning and
 * pronunciation are preserved, only the letters change.
 *
 * The reverse direction (casual romanized text -> native script) is intentionally not
 * offered here: ICU's Indic rules only reverse cleanly for formal ISO 15919 spelling with
 * diacritics, not the ad-hoc phonetic spelling people actually type, so results would be
 * unreliable.
 */
val isTransliterationSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

fun transliterateToLatin(text: String): String? {
    if (!isTransliterationSupported || text.isBlank()) return null
    return AnyToLatinHolder.transliterate(text)
}

// Isolated so the android.icu.text.Transliterator class reference is only ever resolved
// once the SDK check above has passed — keeps this file safe to load on API < 29 devices,
// where the class doesn't exist. The set of available transliterator IDs also varies by
// Android version/device, so getInstance() may still fail even on API 29+.
private object AnyToLatinHolder {
    private val instance: android.icu.text.Transliterator? by lazy {
        try {
            android.icu.text.Transliterator.getInstance("Any-Latin")
        } catch (e: Exception) {
            null
        }
    }

    fun transliterate(text: String): String? = try {
        instance?.transliterate(text)
    } catch (e: Exception) {
        null
    }
}
