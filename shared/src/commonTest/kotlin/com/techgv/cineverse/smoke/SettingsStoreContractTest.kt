package com.techgv.cineverse.smoke

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every [SettingsStore] actual runs this contract (nonWeb: DataStore, wasm: browser storage). */
abstract class SettingsStoreContractTest {
    abstract fun newStore(): SettingsStore

    @Test
    fun writeReadRemove() = runTest {
        val store = newStore()
        val key = "contract-key"
        store.remove(key)
        assertNull(store.string(key).first())

        store.setString(key, "first")
        store.setString(key, "second")
        assertEquals("second", store.string(key).first())

        store.remove(key)
        assertNull(store.string(key).first())
    }
}
