package com.techgv.cineverse.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.presentation.DetailStateHolder
import com.techgv.cineverse.presentation.DetailUiState
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * TV adapter over [DetailStateHolder] (§7.4): focus memory. Button states come from `LocalLibraryActions` in the
 * screen, so the holder gets no library here.
 */
class DetailViewModel(
    val route: Route.Detail,
    handle: SavedStateHandle,
    tmdb: TmdbRepository,
    settings: Flow<Settings>,
    appGeneration: GenerationCounter,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    private val holder = DetailStateHolder(MediaRef(route.mediaType, route.id), tmdb.catalog, settings, appGeneration, scope = viewModelScope)
    val imageBase: String get() = holder.imageBase
    val state: StateFlow<DetailUiState> = holder.state

    fun retry() = holder.retry()
}
