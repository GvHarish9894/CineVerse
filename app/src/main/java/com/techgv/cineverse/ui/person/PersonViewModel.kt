package com.techgv.cineverse.ui.person

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.toPersonDetail
import com.techgv.cineverse.data.tmdb.withEnglishBiography
import com.techgv.cineverse.domain.PersonDetail
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.toSectionErrorKind
import com.techgv.cineverse.ui.detail.ENGLISH
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PersonViewModel(
    val route: Route.Person,
    private val handle: SavedStateHandle,
    private val tmdb: TmdbRepository,
    settings: Flow<Settings>,
    private val appGeneration: GenerationCounter,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    val imageBase: String get() = tmdb.imageBaseUrl()

    /** Screen scalar (§1.3): biography expanded. */
    val bioExpanded: StateFlow<Boolean> = handle.getStateFlow(BIO_EXPANDED, false)

    private val generation = GenerationCounter()
    private val retries = MutableStateFlow(0)
    private val _person = MutableStateFlow<SectionState<PersonDetail>>(SectionState.Loading)
    val person: StateFlow<SectionState<PersonDetail>> = _person.asStateFlow()

    init {
        viewModelScope.launch {
            combine(settings.map { it.language }.distinctUntilChanged(), retries) { language, _ -> language }
                .collectLatest { load(it) }
        }
    }

    fun retry() = retries.update { it + 1 }

    fun toggleBio() {
        handle[BIO_EXPANDED] = !bioExpanded.value
    }

    private suspend fun load(language: String) {
        val gen = generation.bump()
        val appGen = appGeneration.current
        // As DetailViewModel: an app-generation-only supersession reloads for the current settings.
        fun current(): Boolean {
            if (!generation.isCurrent(gen)) return false
            val appCurrent = appGeneration.isCurrent(appGen)
            if (!appCurrent) retry()
            return appCurrent
        }

        if (person.value !is SectionState.Content) _person.value = SectionState.Loading
        val person = try {
            tmdb.person(route.id, language).toPersonDetail()
        } catch (e: TmdbException) {
            if (current()) _person.value = SectionState.Error(e.toSectionErrorKind())
            return
        }
        if (!current()) return
        _person.value = SectionState.Content(person)
        if (language == ENGLISH || person.biography.isNotBlank()) return
        // D7: one cached en-US fetch fills a blank biography; its failure changes nothing.
        val english = try {
            tmdb.person(route.id, ENGLISH).biography
        } catch (_: TmdbException) {
            return
        }
        if (current()) _person.value = SectionState.Content(person.withEnglishBiography(english))
    }

    private companion object {
        const val BIO_EXPANDED = "bioExpanded"
    }
}
