package com.techgv.cineverse.smoke

class BrowserSettingsStoreTest : SettingsStoreContractTest() {
    override fun newStore(): SettingsStore = BrowserSettingsStore(keyPrefix = "cineverse.test.")
}
