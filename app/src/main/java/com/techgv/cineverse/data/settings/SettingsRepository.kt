package com.techgv.cineverse.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/** §7.9 "Use your TMDB account defaults": the signed-in account's content settings, offered until applied or dismissed. */
@Serializable
data class AccountDefaults(val accountObjectId: String, val language: String, val region: String, val includeAdult: Boolean)

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
        val accountDefaults = stringPreferencesKey("account_defaults")
        val accountDefaultsOffered = stringSetPreferencesKey("account_defaults_offered")
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

    /** The pending account-defaults offer, if any (Settings shows it only for that signed-in account). */
    val accountDefaults: Flow<AccountDefaults?> = dataStore.data.map { it.pendingAccountDefaults() }

    private fun Preferences.pendingAccountDefaults(): AccountDefaults? =
        this[Keys.accountDefaults]?.let { runCatching { Json.decodeFromString<AccountDefaults>(it) }.getOrNull() }

    /** Offers [defaults] on the account's first sign-in on this TV only; later sign-ins never re-offer. */
    suspend fun offerAccountDefaults(defaults: AccountDefaults) {
        dataStore.edit { p ->
            val offered = p[Keys.accountDefaultsOffered].orEmpty()
            if (defaults.accountObjectId in offered) return@edit
            p[Keys.accountDefaultsOffered] = offered + defaults.accountObjectId
            p[Keys.accountDefaults] = Json.encodeToString(defaults)
        }
    }

    /** Ends the pending offer; [apply] writes its language / region / include_adult (cache invalidation follows). */
    suspend fun resolveAccountDefaults(apply: Boolean) {
        dataStore.edit { p ->
            val defaults = p.pendingAccountDefaults()
            p.remove(Keys.accountDefaults)
            if (apply && defaults != null) {
                p[Keys.language] = defaults.language
                p[Keys.region] = defaults.region
                p[Keys.includeAdult] = defaults.includeAdult
            }
        }
    }

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
