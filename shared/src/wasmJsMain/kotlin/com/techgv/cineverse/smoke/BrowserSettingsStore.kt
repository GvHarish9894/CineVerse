package com.techgv.cineverse.smoke

import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// DataStore 1.3.0's wasm storage is sessionStorage-only (b/441511612), so web settings use localStorage directly.
// shortcut: same-tab change notification only, no cross-tab `storage` events; upgrade with the web shell (P9).
class BrowserSettingsStore(private val keyPrefix: String = "cineverse.") : SettingsStore {
    private val writes = MutableStateFlow(0)

    override fun string(key: String): Flow<String?> =
        writes.map { localStorage.getItem(keyPrefix + key) }.distinctUntilChanged()

    override suspend fun setString(key: String, value: String) {
        localStorage.setItem(keyPrefix + key, value)
        writes.update { it + 1 }
    }

    override suspend fun remove(key: String) {
        localStorage.removeItem(keyPrefix + key)
        writes.update { it + 1 }
    }
}
