package com.techgv.cineverse.ui.nav

import androidx.annotation.Keep
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.techgv.cineverse.domain.MediaType
import kotlinx.serialization.Serializable

@Keep // route argument: serializer must survive R8
@Serializable
enum class BrowseKind { Movies, Tv, People }

/** Typed routes (§1.2). Routes carry identifiers only; screens reload their own data. */
sealed interface Route {
    @Serializable data object Splash : Route
    @Serializable data object Home : Route
    @Serializable data class Browse(val kind: BrowseKind) : Route
    @Serializable data object Search : Route
    @Serializable data object Library : Route
    @Serializable data object Settings : Route
    @Serializable data object Login : Route
    @Serializable data class Detail(val mediaType: MediaType, val id: Int) : Route
    @Serializable data class Person(val id: Int) : Route
    @Serializable data class Season(val seriesId: Int, val seasonNumber: Int) : Route
    @Serializable data class Player(val videoKey: String, val title: String, val kicker: String) : Route
}

/** Screens that show the rail (§1.2 "Rail visibility"); they are also the reset roots. */
fun NavDestination.isRailScreen(): Boolean =
    hasRoute<Route.Home>() || hasRoute<Route.Browse>() || hasRoute<Route.Search>() ||
        hasRoute<Route.Library>() || hasRoute<Route.Settings>()

/** `reset`: [route] becomes the only back-stack entry (Login can never remain under Home). */
fun NavController.reset(route: Route) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}
