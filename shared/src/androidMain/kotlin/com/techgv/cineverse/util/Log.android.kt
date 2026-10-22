package com.techgv.cineverse.util

import android.util.Log

internal actual fun logWarning(tag: String, message: String) {
    Log.w(tag, message)
}
