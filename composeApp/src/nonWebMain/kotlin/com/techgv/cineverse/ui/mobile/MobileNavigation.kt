package com.techgv.cineverse.ui.mobile

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.presentation.BrowseSelection
import com.techgv.cineverse.presentation.HomeRow
import kotlinx.serialization.Serializable

/** Handoff for subsequent native screen groups; identifiers (plus Detail's optional display seed), never repository objects or credentials. */
sealed interface MobileDestination {
    /** [posterPath]/[title] are an optional display seed from the originating card, never Detail's source of truth. */
    data class Detail(val ref: MediaRef, val posterPath: String? = null, val title: String? = null) : MobileDestination
    data class Person(val id: Int) : MobileDestination
    data class Season(val seriesId: Int, val number: Int) : MobileDestination
    data class Trailer(val key: String, val title: String, val kicker: String) : MobileDestination
    data class Browse(val row: HomeRow, val trend: TrendingWindow = TrendingWindow.Week) : MobileDestination
}

fun interface MobileNavigator { fun open(destination: MobileDestination) }

enum class MobileTab { Home, Explore, Search, Library, Profile }

@Serializable internal data object MobileTabRoot
@Serializable internal data class MobileDetailRoute(val media: String, val id: Int, val posterPath: String? = null, val title: String? = null)
@Serializable internal data class MobilePersonRoute(val id: Int)
@Serializable internal data class MobileSeasonRoute(val seriesId: Int, val number: Int)
@Serializable internal data class MobileTrailerRoute(val key: String, val title: String, val kicker: String)
@Serializable internal data class MobileBrowseRoute(val row: String, val trend: String = TrendingWindow.Week.name)

internal fun MobileBrowseRoute.selection(): BrowseSelection? = try {
    BrowseSelection.fromHomeRow(HomeRow.valueOf(row), TrendingWindow.valueOf(trend))
} catch (_: IllegalArgumentException) {
    null // Reject malformed route scalars without replacing them with a different catalog source.
}

internal fun NavHostController.open(destination: MobileDestination) {
    when (destination) {
        is MobileDestination.Detail -> navigate(MobileDetailRoute(destination.ref.mediaType.path, destination.ref.id, destination.posterPath, destination.title))
        is MobileDestination.Person -> navigate(MobilePersonRoute(destination.id))
        is MobileDestination.Season -> navigate(MobileSeasonRoute(destination.seriesId, destination.number)) {
            // Switching seasons replaces the Season entry, so Back returns to the screen that opened it.
            if (currentDestination?.hasRoute<MobileSeasonRoute>() == true) popUpTo<MobileSeasonRoute> { inclusive = true }
        }
        is MobileDestination.Trailer -> navigate(MobileTrailerRoute(destination.key, destination.title, destination.kicker))
        is MobileDestination.Browse -> navigate(MobileBrowseRoute(destination.row.name, destination.trend.name))
    }
}

internal fun Hero.searchDestination(): MobileDestination = when (this) {
    is Hero.OfTitle -> title.detailDestination()
    is Hero.OfPerson -> MobileDestination.Person(person.id)
}

/** Seeds Detail's header with the card's art so its shared poster exists on Detail's first frame. */
internal fun Title.detailDestination() = MobileDestination.Detail(ref, posterPath, title)
