package com.techgv.cineverse.ui.nav

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The optional Detail seed survives Navigation's argument encoding (paths with '/', titles with URL characters). */
@RunWith(AndroidJUnit4::class)
class RouteDetailSeedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun seededDetail_roundTrips() = assertRoundTrip(
        Route.Detail(MediaType.Tv, 1399, posterPath = "/u3b.jpg", backdropPath = null, title = "Amélie & Co? 100% / #1"),
    )

    @Test
    fun unseededDetail_roundTrips() = assertRoundTrip(Route.Detail(MediaType.Movie, 550))

    private fun assertRoundTrip(route: Route.Detail) {
        var received: Route.Detail? = null
        compose.setContent {
            NavHost(rememberNavController(), startDestination = route) {
                composable<Route.Detail> { received = it.toRoute() }
            }
        }
        compose.waitForIdle()
        assertEquals(route, received)
    }
}
