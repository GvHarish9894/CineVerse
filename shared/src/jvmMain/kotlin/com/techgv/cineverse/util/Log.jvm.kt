package com.techgv.cineverse.util

internal actual fun logWarning(tag: String, message: String) {
    println("W/$tag: $message")
}
