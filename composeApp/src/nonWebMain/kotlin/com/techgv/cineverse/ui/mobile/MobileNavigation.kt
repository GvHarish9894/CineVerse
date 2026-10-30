package com.techgv.cineverse.ui.mobile

import androidx.navigation.NavHostController
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.presentation.BrowseSelection
import com.techgv.cineverse.presentation.HomeRow
import kotlinx.serialization.Serializable

/** Handoff for subsequent native screen groups; only identifiers, never repository objects or credentials. */
sealed interface MobileDestination {
    data class Detail(val ref: MediaRef) : MobileDestination
    data class Person(val id: Int) : MobileDestination
    data class Season(val seriesId: Int, val number: Int) : MobileDestination
    data class Trailer(val key: String, val title: String, val kicker: String) : MobileDestination
    data class Browse(val row: HomeRow, val trend: TrendingWindow = TrendingWindow.Week) : MobileDestination
}

fun interface MobileNavigator { fun open(destination: MobileDestination) }

enum class MobileTab { Home, Explore, Search, Library, Profile }

@Serializable internal data object MobileTabRoot
@Serializable internal data class MobileDetailRoute(val media: String, val id: Int)
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
        is MobileDestination.Detail -> navigate(MobileDetailRoute(destination.ref.mediaType.path, destination.ref.id))
        is MobileDestination.Person -> navigate(MobilePersonRoute(destination.id))
        is MobileDestination.Season -> navigate(MobileSeasonRoute(destination.seriesId, destination.number))
        is MobileDestination.Trailer -> navigate(MobileTrailerRoute(destination.key, destination.title, destination.kicker))
        is MobileDestination.Browse -> navigate(MobileBrowseRoute(destination.row.name, destination.trend.name))
    }
}

internal fun Hero.searchDestination(): MobileDestination = when (this) {
    is Hero.OfTitle -> MobileDestination.Detail(title.ref)
    is Hero.OfPerson -> MobileDestination.Person(person.id)
}
