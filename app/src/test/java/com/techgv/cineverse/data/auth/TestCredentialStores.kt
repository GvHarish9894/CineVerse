package com.techgv.cineverse.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Software AES key standing in for the Android Keystore; [lose] simulates a wiped / restored key. */
class SoftwareKeys : CredentialKeys {
    private var key: SecretKey? = null
    override fun existing() = key
    override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    fun lose() {
        key = null
    }
}

/** Synchronous in-memory DataStore (keeps coroutine tests on virtual time). */
class InMemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: StateFlow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}
