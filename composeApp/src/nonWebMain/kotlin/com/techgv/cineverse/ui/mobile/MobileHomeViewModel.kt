package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.presentation.HomeStateHolder
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

/** Retained by the native root, not by the application graph or a platform host. */
internal class MobileHomeViewModel(
    private val graph: MobileApplication,
    savedState: SavedStateHandle,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val mutableStorageFailed = MutableStateFlow(false)
    val storageFailed = mutableStorageFailed.asStateFlow()
    val home = HomeStateHolder(graph.catalog, graph.generation, graph.settings.filterNotNull(),
        SavedStateHandleStateSaver(savedState), scope ?: viewModelScope,
        onStorageFailure = { mutableStorageFailed.value = true })

    fun retryStorage() {
        mutableStorageFailed.value = false
        graph.retrySettings()
        // Same-valued recovered settings do not emit: explicitly restart this holder's failed reads.
        home.retryStorage()
    }

    override fun onCleared() { home.setActive(false) }
}
