@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.components.EmphasizedEasing
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.ui.theme.LocalReduceMotion

/** Tabs/chips inside one page (Library tabs, a Browse list, Season chips, Search facets) switch in place, never animate. */
private fun switchesInPlace(a: WebRoute, b: WebRoute) = when (a) {
    is WebRoute.Library -> b is WebRoute.Library
    is WebRoute.Season -> b is WebRoute.Season
    is WebRoute.Search -> b is WebRoute.Search
    is WebRoute.Browse -> b is WebRoute.Browse && b.media == a.media
    else -> false
}

private const val PAGE_ENTER_MS = 420
private const val PAGE_EXIT_MS = 220

/** The poster's shared-element key: the Home card and the Detail hero of one title produce the same one. */
internal fun webPosterSharedKey(ref: MediaRef) = "poster:${ref.key}"

private val LocalWebSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalWebRouteVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** A person's shared portrait key: the People card and the Person page of one person produce the same one. */
internal fun webPersonSharedKey(id: Int) = "person:$id"

/**
 * One AnimatedContent child per history entry (WebEntry identity, like the former `key(entry)`), so recompositions with
 * the same entry never remount it. [content] receives its own entry: an exiting route renders its entry, not the new one.
 * Every route change fades through (the kit's page enter: fade + 12dp rise) while the old page fades out, so shared
 * posters/portraits morph between any card and its page. Reduce motion is a cut that never draws the exiting route.
 */
@Composable
internal fun WebRouteTransition(entry: WebEntry, content: @Composable (WebEntry) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val risePx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val routes = updateTransition(entry, label = "webRoute")
    val cut = { reduceMotion || switchesInPlace(routes.segment.initialState.route, routes.segment.targetState.route) }
    SharedTransitionLayout {
        routes.AnimatedContent(transitionSpec = {
            if (cut()) EnterTransition.None togetherWith ExitTransition.None using null
            else (fadeIn(tween(PAGE_ENTER_MS, delayMillis = 60, easing = EmphasizedEasing)) +
                slideInVertically(tween(PAGE_ENTER_MS, delayMillis = 60, easing = EmphasizedEasing)) { risePx }) togetherWith
                fadeOut(tween(PAGE_EXIT_MS, easing = EmphasizedEasing)) using null
        }) { target ->
            // Column: route content keeps the vertical stacking it had directly in the page Column.
            Column(Modifier.graphicsLayer { alpha = if (cut() && transition.targetState == EnterExitState.PostExit) 0f else 1f }) {
                CompositionLocalProvider(LocalWebSharedTransition provides this@SharedTransitionLayout, LocalWebRouteVisibility provides this@AnimatedContent) {
                    content(target)
                }
            }
        }
    }
}

/** Shares this poster across the route transition under [ref]'s key; plain outside a route transition or under Reduce motion. */
@Composable
internal fun Modifier.webSharedPoster(ref: MediaRef): Modifier = webShared(webPosterSharedKey(ref))

/** Shares this portrait across the route transition under the person's key; same rules as [webSharedPoster]. */
@Composable
internal fun Modifier.webSharedPerson(id: Int): Modifier = webShared(webPersonSharedKey(id))

@Composable
private fun Modifier.webShared(key: String): Modifier {
    val shared = LocalWebSharedTransition.current
    val route = LocalWebRouteVisibility.current
    if (shared == null || route == null || LocalReduceMotion.current) return this
    return with(shared) { this@webShared.sharedElement(rememberSharedContentState(key), route) }
}

/**
 * Card click for a morphing route change: offers [seed] (the card's title + poster or name + photo) to exactly [route]
 * just before [open] navigates, so the page has the shared art on its first frame. Outside the web root it just opens.
 */
@Composable
internal fun seededOpen(route: WebRoute, seed: Map<String, String>, open: () -> Unit): () -> Unit {
    val offer = LocalWebSeedOffer.current
    return { offer?.offer(route, seed); open() }
}
