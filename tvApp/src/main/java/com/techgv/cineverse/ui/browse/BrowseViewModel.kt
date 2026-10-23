package com.techgv.cineverse.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.presentation.BrowseStateHolder
import com.techgv.cineverse.presentation.BrowseUiState
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.nav.BrowseKind
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** TV adapter over [BrowseStateHolder] (§7.3): focus memory; chip selection changes on focus. */
class BrowseViewModel(
    repository: TmdbRepository,
    appGeneration: GenerationCounter,
    settings: Flow<Settings>,
    handle: SavedStateHandle,
    kind: BrowseKind,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    private val holder = BrowseStateHolder(repository.catalog, appGeneration, settings, SavedStateHandleStateSaver(handle), kind.toHolderKind(), viewModelScope)
    val tabs = holder.tabs
    val uiState: StateFlow<BrowseUiState> = holder.uiState

    /** Chip focus (§7.3): selection changes on focus. */
    fun selectTab(index: Int) = holder.selectTab(index)

    fun loadMore() = holder.loadMore()

    fun retry() = holder.retry()
}

/** The route enum (fixed FQN, see [BrowseKind]) → the shared holder's enum. */
fun BrowseKind.toHolderKind(): com.techgv.cineverse.presentation.BrowseKind = when (this) {
    BrowseKind.Movies -> com.techgv.cineverse.presentation.BrowseKind.Movies
    BrowseKind.Tv -> com.techgv.cineverse.presentation.BrowseKind.Tv
    BrowseKind.People -> com.techgv.cineverse.presentation.BrowseKind.People
}
