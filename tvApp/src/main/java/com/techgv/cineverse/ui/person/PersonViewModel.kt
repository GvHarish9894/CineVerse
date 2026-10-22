package com.techgv.cineverse.ui.person

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.domain.PersonDetail
import com.techgv.cineverse.presentation.PersonStateHolder
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** TV adapter over [PersonStateHolder] (§7.5): focus memory. */
class PersonViewModel(
    val route: Route.Person,
    handle: SavedStateHandle,
    tmdb: TmdbRepository,
    settings: Flow<Settings>,
    appGeneration: GenerationCounter,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    private val holder = PersonStateHolder(route.id, tmdb.catalog, settings, appGeneration, SavedStateHandleStateSaver(handle), viewModelScope)
    val imageBase: String get() = holder.imageBase

    /** Screen scalar (§1.3): biography expanded. */
    val bioExpanded: StateFlow<Boolean> = holder.bioExpanded
    val person: StateFlow<SectionState<PersonDetail>> = holder.person

    fun retry() = holder.retry()

    fun toggleBio() = holder.toggleBio()
}
