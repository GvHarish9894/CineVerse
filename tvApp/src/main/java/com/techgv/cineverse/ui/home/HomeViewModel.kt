package com.techgv.cineverse.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.presentation.HomeRow
import com.techgv.cineverse.presentation.HomeStateHolder
import com.techgv.cineverse.presentation.HomeUiState
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import com.techgv.cineverse.presentation.TrailerEvent
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

sealed interface HomeEvent {
    data class Open(val route: Route) : HomeEvent
    data class Toast(val title: String, val subtitle: String?) : HomeEvent
}

/**
 * TV adapter over [HomeStateHolder]: focus memory, the focus-driven hero (120 ms debounce), "active" = RESUMED and
 * window-focused (a Dialog took focus), and trailer events as Player routes / toasts.
 */
class HomeViewModel(
    repository: TmdbRepository,
    appGeneration: GenerationCounter,
    settings: Flow<Settings>,
    handle: SavedStateHandle,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    private val holder = HomeStateHolder(repository.catalog, appGeneration, settings, SavedStateHandleStateSaver(handle), viewModelScope)
    private var resumed = false
    private var windowFocused = true

    val uiState: StateFlow<HomeUiState> = holder.uiState

    /** Not replayed: an event without a (started) collector is dropped, never delivered on return. */
    val events: Flow<HomeEvent> = holder.events.map { event ->
        when (event) {
            is TrailerEvent.Play -> HomeEvent.Open(Route.Player(event.videoKey, title = event.title, kicker = event.kicker))
            is TrailerEvent.Unavailable -> HomeEvent.Toast("No trailer available", event.title)
            TrailerEvent.Failed -> HomeEvent.Toast("Couldn’t load the trailer", "Try again")
        }
    }

    /** Home's lifecycle is RESUMED; a trailer fetch outliving it never navigates (on top of the next screen or on a later return). */
    fun setActive(active: Boolean) {
        resumed = active
        holder.setActive(resumed && windowFocused)
    }

    /** A Dialog (Exit, Rate, Import) took the window focus: a trailer fetch never opens the Player under it. */
    fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
        holder.setActive(resumed && windowFocused)
    }

    fun retry(row: HomeRow) = holder.retry(row)

    /** Trending chips (OK): switches the window and reloads only the trending row. */
    fun selectTrend(window: TrendingWindow) = holder.selectTrend(window)

    /** §7.2: the hero follows focus after 120 ms; focus moving on cancels the pending change. */
    fun onItemFocused(hero: Hero) = holder.selectHero(hero, HERO_DEBOUNCE_MILLIS)

    /** Play trailer from Home (§7.2), see [HomeStateHolder.playTrailer]. */
    fun playTrailer(title: Title) = holder.playTrailer(title)

    private companion object {
        const val HERO_DEBOUNCE_MILLIS = 120L
    }
}
