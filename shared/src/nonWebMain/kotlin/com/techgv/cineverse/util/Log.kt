package com.techgv.cineverse.util

/** A warning for the platform log (Android: logcat). Never pass token or session values. */
internal expect fun logWarning(tag: String, message: String)
