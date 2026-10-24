package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.presentation.HomeStateHolder
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import kotlinx.coroutines.flow.filterNotNull

/** Retained by the native root, not by the application graph or a platform host. */
internal class MobileHomeViewModel(graph: MobileApplication, savedState: SavedStateHandle) : ViewModel() {
    val home = HomeStateHolder(graph.catalog, graph.generation, graph.settings.filterNotNull(),
        SavedStateHandleStateSaver(savedState), viewModelScope)

    override fun onCleared() { home.setActive(false) }
}
