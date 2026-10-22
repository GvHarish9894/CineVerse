package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.toPersonDetail
import com.techgv.cineverse.data.tmdb.withEnglishBiography
import com.techgv.cineverse.domain.PersonDetail
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
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

/**
 * §7.5 Person [personId] with its combined credits; a blank biography in a non-`en-US` language is filled from one
 * cached `en-US` fetch (D7). Reload rules as [DetailStateHolder]. [scope]: see [HomeStateHolder].
 */
class PersonStateHolder(
    val personId: Int,
    private val catalog: CatalogRepository,
    settings: Flow<Settings>,
    private val appGeneration: GenerationCounter,
    private val saver: StateSaver,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    val imageBase: String get() = catalog.imageBaseUrl()

    private val _bioExpanded = MutableStateFlow(saver.getBoolean(BIO_EXPANDED) ?: false)

    /** Screen scalar (§1.3, saved): biography expanded. */
    val bioExpanded: StateFlow<Boolean> = _bioExpanded.asStateFlow()

    private val generation = GenerationCounter()
    private val retries = MutableStateFlow(0)
    private val _person = MutableStateFlow<SectionState<PersonDetail>>(SectionState.Loading)
    val person: StateFlow<SectionState<PersonDetail>> = _person.asStateFlow()

    init {
        this.scope.launch {
            combine(settings.map { it.language }.distinctUntilChanged(), retries) { language, _ -> language }
                .collectLatest { load(it) }
        }
    }

    fun retry() = retries.update { it + 1 }

    fun toggleBio() {
        val expanded = !_bioExpanded.value
        saver.putBoolean(BIO_EXPANDED, expanded)
        _bioExpanded.value = expanded
    }

    private suspend fun load(language: String) {
        val gen = generation.bump()
        val appGen = appGeneration.current
        // As DetailStateHolder: an app-generation-only supersession reloads for the current settings.
        fun current(): Boolean {
            if (!generation.isCurrent(gen)) return false
            val appCurrent = appGeneration.isCurrent(appGen)
            if (!appCurrent) retry()
            return appCurrent
        }

        if (person.value !is SectionState.Content) _person.value = SectionState.Loading
        val person = try {
            catalog.person(personId, language).toPersonDetail()
        } catch (e: TmdbException) {
            if (current()) _person.value = SectionState.Error(e.toSectionErrorKind())
            return
        }
        if (!current()) return
        _person.value = SectionState.Content(person)
        if (language == ENGLISH || person.biography.isNotBlank()) return
        // D7: one cached en-US fetch fills a blank biography; its failure changes nothing.
        val english = optional { catalog.person(personId, ENGLISH).biography } ?: return
        if (current()) _person.value = SectionState.Content(person.withEnglishBiography(english))
    }

    private companion object {
        const val BIO_EXPANDED = "bioExpanded"
    }
}
