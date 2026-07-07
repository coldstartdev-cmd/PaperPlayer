package com.jp.paperplayer.tagging

import org.jaudiotagger.audio.exceptions.NoWritePermissionsException

// JAudioTagger wraps OS errors in its own exception types, so we walk the full
// cause chain and also check the top-level message string (which JAudioTagger
// builds by embedding the cause's toString()).
fun isPermissionError(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) {
        if (t is SecurityException) return true
        if (t is NoWritePermissionsException) return true
        val msg = t.message ?: ""
        if (msg.contains("EACCES") ||
            msg.contains("Permission denied", ignoreCase = true) ||
            msg.contains("do not have permissions", ignoreCase = true) ||
            msg.contains("permissions to modify", ignoreCase = true)
        ) return true
        t = t.cause
    }
    return false
}
