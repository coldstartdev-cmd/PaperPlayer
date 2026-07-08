package com.jp.paperplayer.data

import android.content.Context

enum class ShuffleStrategy {
    /** Favors less-played songs — groups by play count and shuffles within each group. */
    SMART,

    /** Plain, uniformly random order with no weighting. */
    RANDOM,
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getShuffleStrategy(): ShuffleStrategy =
        prefs.getString(KEY_SHUFFLE_STRATEGY, null)
            ?.let { runCatching { ShuffleStrategy.valueOf(it) }.getOrNull() }
            ?: ShuffleStrategy.SMART

    fun setShuffleStrategy(strategy: ShuffleStrategy) {
        prefs.edit().putString(KEY_SHUFFLE_STRATEGY, strategy.name).apply()
    }

    fun getExcludedFolders(): Set<String> =
        prefs.getStringSet(KEY_EXCLUDED_FOLDERS, null)?.toSet() ?: emptySet()

    fun setExcludedFolders(folders: Set<String>) {
        prefs.edit().putStringSet(KEY_EXCLUDED_FOLDERS, folders.toSet()).apply()
    }

    private companion object {
        const val KEY_SHUFFLE_STRATEGY = "shuffle_strategy"
        const val KEY_EXCLUDED_FOLDERS = "excluded_folders"
    }
}
