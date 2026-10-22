package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.UserAccessToken
import kotlinx.coroutines.flow.Flow

// Native only (plan §3): the browser never holds these; common code sees the account through AuthSession.

/** The signed-in tuple of §4.1 step 5; persisted only as a whole. */
data class Credentials(
    val userAccessToken: UserAccessToken,
    val accountObjectId: AccountObjectId,
    val sessionId: SessionId,
    val accountId: AccountId,
    val username: String,
    val name: String?,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: username
}

/** Persists [Credentials] atomically; an incomplete or undecryptable tuple reads as signed out (null). */
interface CredentialStore {
    val credentials: Flow<Credentials?>
    suspend fun save(credentials: Credentials)
    suspend fun clear()
}
