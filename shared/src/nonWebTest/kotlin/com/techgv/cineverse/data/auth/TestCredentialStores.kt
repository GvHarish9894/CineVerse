// Shared copy of tvApp/src/test/.../data/auth/TestCredentialStores.kt (minus the JVM SoftwareKeys: shared tests use
// testTokenCipher()); keep both in step. A shared test-fixtures module is deferred (plan K9 / build-logic).
package com.techgv.cineverse.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.IOException

/** Synchronous in-memory DataStore (keeps coroutine tests on virtual time). */
class InMemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: StateFlow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

/** [InMemoryPreferencesStore] whose writes can be held at [gate] or made to fail with [failWrites]. */
class GatedPreferencesStore : DataStore<Preferences> {
    private val delegate = InMemoryPreferencesStore()
    var gate: CompletableDeferred<Unit>? = null
    var failWrites = false
    override val data: Flow<Preferences> = delegate.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        gate?.await()
        if (failWrites) throw IOException("disk full")
        return delegate.updateData(transform)
    }
}
