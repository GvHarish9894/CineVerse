package com.techgv.cineverse.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.techgv.cineverse.data.auth.DataStoreCredentialStore
import okio.Path
import org.koin.core.module.Module
import org.koin.core.scope.Scope

/** iOS / jvm: both Preferences DataStores as `<name>.preferences_pb` files in [directory]. */
internal fun Module.dataStoreFiles(directory: Scope.() -> Path) {
    single(SettingsDataStore) { PreferenceDataStoreFactory.createWithPath { directory() / "settings.preferences_pb" } }
    single(CredentialsDataStore) {
        PreferenceDataStoreFactory.createWithPath { directory() / "${DataStoreCredentialStore.FILE_NAME}.preferences_pb" }
    }
}
