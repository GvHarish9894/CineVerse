package com.techgv.cineverse.data.settings

import kotlinx.serialization.Serializable

enum class HapticStrength(val millis: Long) { Light(6), Medium(12), Strong(20) }

/** §7.9 settings with their defaults. */
data class Settings(
    val haptics: Boolean = true,
    val strength: HapticStrength = HapticStrength.Medium,
    val sounds: Boolean = false,
    val glow: Boolean = true,
    val motion: Boolean = false,
    val language: String = "en-US",
    val region: String = "IN",
    val includeAdult: Boolean = false,
) {
    companion object {
        val LANGUAGES = listOf("en-US", "hi-IN", "ta-IN", "ml-IN", "es-ES")
        val REGIONS = listOf("IN", "US", "GB")
    }
}

/** §7.9 "Use your TMDB account defaults": an account's content settings, offered until applied or dismissed. */
@Serializable
data class AccountDefaults(val accountObjectId: String, val language: String, val region: String, val includeAdult: Boolean)
