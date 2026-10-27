package com.techgv.cineverse.ui.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.DataStoreSettingsStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.CatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class NativeMobileStorageBoundaryFixture(scope: CoroutineScope) {
    private val dependencies = MobileFixture(scope).also { it.graph.close() }
    val native = NativeMobileBoundaryDataStore()
    val settings = SettingsRepository(DataStoreSettingsStore(native))
    val graph = MobileApplication(
        CatalogRepository(dependencies.api, dependencies.generation, settings::current),
        dependencies.generation, settings, dependencies.auth, dependencies.library,
        RecentQueryStore(DataStoreSettingsStore(native)), scope,
    )
}

/** Inject native DataStore errors through the production adapter, never synthetic StorageExceptions. */
internal class NativeMobileBoundaryDataStore : DataStore<Preferences> {
    private val snapshots = MutableStateFlow<Pair<Preferences, Throwable?>>(emptyPreferences() to null)
    var readFailure: Throwable?
        get() = snapshots.value.second
        set(value) { snapshots.value = snapshots.value.first to value }
    var writeFailure: Throwable? = null
    var edits = 0
        private set
    override val data = snapshots.map { (values, failure) ->
        failure?.let { throw it }
        values
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        edits++
        val next = transform(snapshots.value.first)
        writeFailure?.let { throw it }
        snapshots.value = next to null
        return next
    }
}
