package com.techgv.cineverse.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Separate from v1 credentials/settings; the complete pending tuple is encrypted in one atomic edit. */
class DataStorePendingAttemptStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : PendingAttemptStore {
    override suspend fun save(attempt: ApprovalAttempt) {
        val encrypted = cipher.encrypt(Json.encodeToString(StoredAttempt(attempt.id, attempt.code.requestToken)))
        dataStore.edit { it.clear(); it[encryptedAttempt] = encrypted }
    }

    override suspend fun load(): ApprovalAttempt? {
        val encrypted = dataStore.data.first()[encryptedAttempt] ?: return null
        val plain = cipher.decrypt(encrypted) ?: return null
        val stored = try {
            Json.decodeFromString<StoredAttempt>(plain)
        } catch (_: SerializationException) {
            return null
        }
        if (!Regex("[A-Za-z0-9_-]+").matches(stored.id) || stored.token.isBlank()) return null
        return ApprovalAttempt(stored.id, RequestCode(stored.token))
    }

    override suspend fun clear() { dataStore.edit { it.clear() } }

    @Serializable
    private class StoredAttempt(val id: String, val token: String)

    companion object {
        const val FILE_NAME = "pending_approval"
        private val encryptedAttempt = stringPreferencesKey("attempt_enc")
    }
}
