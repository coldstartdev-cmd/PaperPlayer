package com.jp.paperplayer.data

import android.content.Context

class PlayCountStore(context: Context) {
    private val prefs = context.getSharedPreferences("play_counts", Context.MODE_PRIVATE)

    fun increment(songId: Long) {
        val key = songId.toString()
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun getCount(songId: Long): Int = prefs.getInt(songId.toString(), 0)

    fun getAllCounts(): Map<Long, Int> = prefs.all.mapNotNull { (k, v) ->
        val id = k.toLongOrNull() ?: return@mapNotNull null
        val count = v as? Int ?: return@mapNotNull null
        id to count
    }.toMap()
}
