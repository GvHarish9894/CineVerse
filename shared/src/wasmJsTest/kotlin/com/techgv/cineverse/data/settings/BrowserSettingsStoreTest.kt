package com.techgv.cineverse.data.settings

class BrowserSettingsStoreTest : SettingsStoreContractTest() {
    override fun newStore(): SettingsStore = BrowserSettingsStore(keyPrefix = "cineverse.test.")
}
