package com.techgv.cineverse.data.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every [SettingsStore] actual runs this contract (nonWeb: DataStore, wasm: browser storage). */
abstract class SettingsStoreContractTest {
    abstract fun newStore(): SettingsStore

    @Test
    fun typedWriteReadRemove() = runTest {
        val store = newStore()
        store.edit { it.remove("language"); it.remove("haptics"); it.remove("resolved") }
        val empty = store.data.first()
        assertNull(empty.string("language"))
        assertNull(empty.boolean("haptics"))
        assertNull(empty.stringSet("resolved"))

        store.edit { it.setString("language", "en-US") }
        store.edit {
            it.setString("language", "hi-IN")
            it.setBoolean("haptics", false)
            it.setStringSet("resolved", setOf("a", "b"))
        }
        val written = store.data.first()
        assertEquals("hi-IN", written.string("language"))
        assertEquals(false, written.boolean("haptics"))
        assertEquals(setOf("a", "b"), written.stringSet("resolved"))
        assertNull(written.boolean("language")) // another type reads as null

        store.edit { it.remove("language"); it.remove("haptics"); it.remove("resolved") }
        val removed = store.data.first()
        assertNull(removed.string("language"))
        assertNull(removed.boolean("haptics"))
        assertNull(removed.stringSet("resolved"))
    }

    @Test
    fun editSeesItsOwnSnapshotAndWritesAllKeysTogether() = runTest {
        val store = newStore()
        store.edit { it.setString("counter", "1"); it.remove("copy") }
        store.edit { values ->
            val counter = values.string("counter")!!.toInt()
            values.setString("counter", "${counter + 1}")
            values.setString("copy", "${counter + 1}")
        }
        val after = store.data.first()
        assertEquals("2", after.string("counter"))
        assertEquals("2", after.string("copy"))
        store.edit { it.remove("counter"); it.remove("copy") }
    }
}
