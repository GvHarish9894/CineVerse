package com.techgv.cineverse.data.settings

/** The test double keeps the [SettingsStore] contract too. */
class InMemorySettingsStoreTest : SettingsStoreContractTest() {
    override fun newStore(): SettingsStore = InMemorySettingsStore()
}
