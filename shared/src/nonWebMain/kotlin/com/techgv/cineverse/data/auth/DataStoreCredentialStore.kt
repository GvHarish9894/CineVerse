package com.techgv.cineverse.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.UserAccessToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Credential DataStore (TV: file `credentials`, excluded from backup; key names unchanged since TV v1). The tuple is
 * written in one `edit` (atomic); an incomplete tuple or an undecryptable secret (key lost) reads as signed out.
 */
class DataStoreCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : CredentialStore {
    private object Keys {
        val userAccessToken = stringPreferencesKey("user_access_token_enc")
        val sessionId = stringPreferencesKey("session_id_enc")
        val accountObjectId = stringPreferencesKey("account_object_id")
        val accountId = intPreferencesKey("account_id")
        val username = stringPreferencesKey("username")
        val name = stringPreferencesKey("name")
    }

    override val credentials: Flow<Credentials?> = dataStore.data.map { p ->
        val token = p[Keys.userAccessToken]?.let(cipher::decrypt) ?: return@map null
        val session = p[Keys.sessionId]?.let(cipher::decrypt) ?: return@map null
        Credentials(
            userAccessToken = UserAccessToken(token),
            accountObjectId = AccountObjectId(p[Keys.accountObjectId] ?: return@map null),
            sessionId = SessionId(session),
            accountId = AccountId(p[Keys.accountId] ?: return@map null),
            username = p[Keys.username] ?: return@map null,
            name = p[Keys.name],
        )
    }

    override suspend fun save(credentials: Credentials) {
        val token = cipher.encrypt(credentials.userAccessToken.value)
        val session = cipher.encrypt(credentials.sessionId.value)
        dataStore.edit { p ->
            p.clear()
            p[Keys.userAccessToken] = token
            p[Keys.sessionId] = session
            p[Keys.accountObjectId] = credentials.accountObjectId.value
            p[Keys.accountId] = credentials.accountId.value
            p[Keys.username] = credentials.username
            credentials.name?.let { p[Keys.name] = it }
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    companion object {
        /** DataStore name; the file is `files/datastore/credentials.preferences_pb` (see backup rules). */
        const val FILE_NAME = "credentials"
    }
}
