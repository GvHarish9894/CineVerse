package com.techgv.cineverse.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val haptics = booleanPreferencesKey("haptics")
        val strength = stringPreferencesKey("strength")
        val sounds = booleanPreferencesKey("sounds")
        val glow = booleanPreferencesKey("glow")
        val motion = booleanPreferencesKey("motion")
        val language = stringPreferencesKey("language")
        val region = stringPreferencesKey("region")
        val includeAdult = booleanPreferencesKey("include_adult")
    }

    val settings: Flow<Settings> = dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): Settings {
        val p = this
        val d = Settings()
        return Settings(
            haptics = p[Keys.haptics] ?: d.haptics,
            strength = HapticStrength.entries.firstOrNull { it.name == p[Keys.strength] } ?: d.strength,
            sounds = p[Keys.sounds] ?: d.sounds,
            glow = p[Keys.glow] ?: d.glow,
            motion = p[Keys.motion] ?: d.motion,
            language = p[Keys.language]?.takeIf { it in Settings.LANGUAGES } ?: d.language,
            region = p[Keys.region]?.takeIf { it in Settings.REGIONS } ?: d.region,
            includeAdult = p[Keys.includeAdult] ?: d.includeAdult,
        )
    }

    suspend fun current(): Settings = settings.first()

    /** Atomic read-modify-write of all keys. */
    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[Keys.haptics] = s.haptics
            p[Keys.strength] = s.strength.name
            p[Keys.sounds] = s.sounds
            p[Keys.glow] = s.glow
            p[Keys.motion] = s.motion
            p[Keys.language] = s.language
            p[Keys.region] = s.region
            p[Keys.includeAdult] = s.includeAdult
        }
    }
}
