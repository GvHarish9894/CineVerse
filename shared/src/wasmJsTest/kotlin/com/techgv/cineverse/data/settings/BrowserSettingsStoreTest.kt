package com.techgv.cineverse.data.settings

import com.techgv.cineverse.data.storage.BrowserChanges
import com.techgv.cineverse.data.storage.BrowserDatabase
import kotlin.test.AfterTest

class BrowserSettingsStoreTest : SettingsStoreContractTest() {
    private val database = BrowserDatabase("cineverse.settings.contract.test")
    private val changes = BrowserChanges("cineverse.settings.contract.test.changes")
    override fun newStore(): SettingsStore = BrowserSettingsStore("cineverse.settings.contract.test.", database, changes)

    @AfterTest
    fun closeResources() { database.close(); changes.close() }
}
