package com.techgv.cineverse.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** §7.9 settings over a [SettingsStore]; key names and value types unchanged since TV v1 (DataStore `settings`). */
class SettingsRepository(private val store: SettingsStore) {
    private object Keys {
        const val HAPTICS = "haptics" // boolean
        const val STRENGTH = "strength" // string: HapticStrength name
        const val SOUNDS = "sounds" // boolean
        const val GLOW = "glow" // boolean
        const val MOTION = "motion" // boolean
        const val LANGUAGE = "language" // string
        const val REGION = "region" // string
        const val INCLUDE_ADULT = "include_adult" // boolean
        const val ACCOUNT_DEFAULTS_PENDING = "account_defaults_pending" // string: JSON map
        const val ACCOUNT_DEFAULTS_RESOLVED = "account_defaults_resolved" // string set
    }

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    private fun SettingsValues.toSettings(): Settings {
        val d = Settings()
        return Settings(
            haptics = boolean(Keys.HAPTICS) ?: d.haptics,
            strength = HapticStrength.entries.firstOrNull { it.name == string(Keys.STRENGTH) } ?: d.strength,
            sounds = boolean(Keys.SOUNDS) ?: d.sounds,
            glow = boolean(Keys.GLOW) ?: d.glow,
            motion = boolean(Keys.MOTION) ?: d.motion,
            language = string(Keys.LANGUAGE)?.takeIf { it in Settings.LANGUAGES } ?: d.language,
            region = string(Keys.REGION)?.takeIf { it in Settings.REGIONS } ?: d.region,
            includeAdult = boolean(Keys.INCLUDE_ADULT) ?: d.includeAdult,
        )
    }

    suspend fun current(): Settings = settings.first()

    /** Unresolved offers by account_object_id (Settings shows only the signed-in account's). */
    val pendingAccountDefaults: Flow<Map<String, AccountDefaults>> = store.data.map { it.pendingAccountDefaults() }

    private fun SettingsValues.pendingAccountDefaults(): Map<String, AccountDefaults> =
        string(Keys.ACCOUNT_DEFAULTS_PENDING)?.let { runCatching { Json.decodeFromString<Map<String, AccountDefaults>>(it) }.getOrNull() }.orEmpty()

    /** Keeps [defaults] pending for its account until Apply / Dismiss; showing it resolves nothing. */
    suspend fun offerAccountDefaults(defaults: AccountDefaults) {
        store.edit { p ->
            if (defaults.accountObjectId in p.stringSet(Keys.ACCOUNT_DEFAULTS_RESOLVED).orEmpty()) return@edit
            p.setString(Keys.ACCOUNT_DEFAULTS_PENDING, Json.encodeToString(p.pendingAccountDefaults() + (defaults.accountObjectId to defaults)))
        }
    }

    /**
     * Ends [accountObjectId]'s pending offer only (never another account's); [apply] writes its
     * language / region / include_adult (cache invalidation follows).
     */
    suspend fun resolveAccountDefaults(accountObjectId: String, apply: Boolean) {
        store.edit { p ->
            val pending = p.pendingAccountDefaults()
            val defaults = pending[accountObjectId] ?: return@edit
            p.setString(Keys.ACCOUNT_DEFAULTS_PENDING, Json.encodeToString(pending - accountObjectId))
            p.setStringSet(Keys.ACCOUNT_DEFAULTS_RESOLVED, p.stringSet(Keys.ACCOUNT_DEFAULTS_RESOLVED).orEmpty() + accountObjectId)
            if (apply) {
                p.setString(Keys.LANGUAGE, defaults.language)
                p.setString(Keys.REGION, defaults.region)
                p.setBoolean(Keys.INCLUDE_ADULT, defaults.includeAdult)
            }
        }
    }

    /** Atomic read-modify-write of all keys. */
    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { p ->
            val s = transform(p.toSettings())
            p.setBoolean(Keys.HAPTICS, s.haptics)
            p.setString(Keys.STRENGTH, s.strength.name)
            p.setBoolean(Keys.SOUNDS, s.sounds)
            p.setBoolean(Keys.GLOW, s.glow)
            p.setBoolean(Keys.MOTION, s.motion)
            p.setString(Keys.LANGUAGE, s.language)
            p.setString(Keys.REGION, s.region)
            p.setBoolean(Keys.INCLUDE_ADULT, s.includeAdult)
        }
    }
}
