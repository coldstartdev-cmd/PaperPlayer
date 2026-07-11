package com.jp.paperplayer.data

import android.content.Context

enum class ShuffleStrategy {
    /** Favors less-played songs — groups by play count and shuffles within each group. */
    SMART,

    /** Plain, uniformly random order with no weighting. */
    RANDOM,
}

enum class PartyFileTransferMode {
    /** Guest downloads the whole file before playing — the original, most reliable behavior. */
    DOWNLOAD,

    /** Guest plays directly from the host's HTTP URL, no local copy. */
    STREAM,
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

    /** Display name shown to other devices in party mode; blank means device model. */
    fun getPartyDeviceName(): String = prefs.getString(KEY_PARTY_DEVICE_NAME, null) ?: ""

    fun setPartyDeviceName(name: String) {
        prefs.edit().putString(KEY_PARTY_DEVICE_NAME, name).apply()
    }

    /**
     * Manual audio-latency trim for party mode, in milliseconds. Positive means
     * this device's speaker is late, so its player runs ahead to compensate.
     */
    fun getPartyLatencyTrimMs(): Long = prefs.getLong(KEY_PARTY_LATENCY_TRIM, 0L)

    fun setPartyLatencyTrimMs(trimMs: Long) {
        prefs.edit().putLong(KEY_PARTY_LATENCY_TRIM, trimMs).apply()
    }

    /** How a guest gets a song's bytes before playing it; see [PartyFileTransferMode]. */
    fun getPartyFileTransferMode(): PartyFileTransferMode =
        prefs.getString(KEY_PARTY_FILE_TRANSFER_MODE, null)
            ?.let { runCatching { PartyFileTransferMode.valueOf(it) }.getOrNull() }
            ?: PartyFileTransferMode.DOWNLOAD

    fun setPartyFileTransferMode(mode: PartyFileTransferMode) {
        prefs.edit().putString(KEY_PARTY_FILE_TRANSFER_MODE, mode.name).apply()
    }

    private companion object {
        const val KEY_SHUFFLE_STRATEGY = "shuffle_strategy"
        const val KEY_EXCLUDED_FOLDERS = "excluded_folders"
        const val KEY_PARTY_DEVICE_NAME = "party_device_name"
        const val KEY_PARTY_LATENCY_TRIM = "party_latency_trim"
        const val KEY_PARTY_FILE_TRANSFER_MODE = "party_file_transfer_mode"
    }
}
