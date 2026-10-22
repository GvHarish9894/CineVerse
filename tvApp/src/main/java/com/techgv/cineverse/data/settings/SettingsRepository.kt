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
import kotlinx.serialization.json.Json

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
        val accountDefaultsPending = stringPreferencesKey("account_defaults_pending")
        val accountDefaultsResolved = stringSetPreferencesKey("account_defaults_resolved")
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

    /** Unresolved offers by account_object_id (Settings shows only the signed-in account's). */
    val pendingAccountDefaults: Flow<Map<String, AccountDefaults>> = dataStore.data.map { it.pendingAccountDefaults() }

    private fun Preferences.pendingAccountDefaults(): Map<String, AccountDefaults> =
        this[Keys.accountDefaultsPending]?.let { runCatching { Json.decodeFromString<Map<String, AccountDefaults>>(it) }.getOrNull() }.orEmpty()

    /** Keeps [defaults] pending for its account until Apply / Dismiss; showing it resolves nothing. */
    suspend fun offerAccountDefaults(defaults: AccountDefaults) {
        dataStore.edit { p ->
            if (defaults.accountObjectId in p[Keys.accountDefaultsResolved].orEmpty()) return@edit
            p[Keys.accountDefaultsPending] = Json.encodeToString(p.pendingAccountDefaults() + (defaults.accountObjectId to defaults))
        }
    }

    /**
     * Ends [accountObjectId]'s pending offer only (never another account's); [apply] writes its
     * language / region / include_adult (cache invalidation follows).
     */
    suspend fun resolveAccountDefaults(accountObjectId: String, apply: Boolean) {
        dataStore.edit { p ->
            val pending = p.pendingAccountDefaults()
            val defaults = pending[accountObjectId] ?: return@edit
            p[Keys.accountDefaultsPending] = Json.encodeToString(pending - accountObjectId)
            p[Keys.accountDefaultsResolved] = p[Keys.accountDefaultsResolved].orEmpty() + accountObjectId
            if (apply) {
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
