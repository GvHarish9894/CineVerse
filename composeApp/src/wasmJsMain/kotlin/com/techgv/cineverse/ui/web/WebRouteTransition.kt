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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.ui.theme.LocalReduceMotion

/** The poster's shared-element key: the Home card and the Detail hero of one title produce the same one. */
internal fun webPosterSharedKey(ref: MediaRef) = "poster:${ref.key}"

private val LocalWebSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalWebRouteVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Only Home <-> Detail carries a shared poster, so only that pair fades; every other route change stays a cut. */
private fun morphs(from: WebRoute, to: WebRoute) = (from == WebRoute.Home && to is WebRoute.Detail) || (from is WebRoute.Detail && to == WebRoute.Home)

/**
 * One AnimatedContent child per history entry (WebEntry identity, like the former `key(entry)`), so recompositions with
 * the same entry never remount it. [content] receives its own entry: an exiting route renders its entry, not the new one.
 * A cut (Reduce motion, or any pair but Home <-> Detail) has no fade or size change and never draws the exiting route.
 */
@Composable
internal fun WebRouteTransition(entry: WebEntry, content: @Composable (WebEntry) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val routes = updateTransition(entry, label = "webRoute")
    val morph = { !reduceMotion && morphs(routes.segment.initialState.route, routes.segment.targetState.route) }
    SharedTransitionLayout {
        routes.AnimatedContent(transitionSpec = {
            (if (morph()) fadeIn() togetherWith fadeOut() else EnterTransition.None togetherWith ExitTransition.None) using null
        }) { target ->
            // Column: route content keeps the vertical stacking it had directly in the page Column.
            Column(Modifier.graphicsLayer { alpha = if (!morph() && transition.targetState == EnterExitState.PostExit) 0f else 1f }) {
                CompositionLocalProvider(LocalWebSharedTransition provides this@SharedTransitionLayout, LocalWebRouteVisibility provides this@AnimatedContent) {
                    content(target)
                }
            }
        }
    }
}

/** Shares this poster across the route transition under [ref]'s key; plain outside a route transition or under Reduce motion. */
@Composable
internal fun Modifier.webSharedPoster(ref: MediaRef): Modifier {
    val shared = LocalWebSharedTransition.current
    val route = LocalWebRouteVisibility.current
    if (shared == null || route == null || LocalReduceMotion.current) return this
    return with(shared) { this@webSharedPoster.sharedElement(rememberSharedContentState(webPosterSharedKey(ref)), route) }
}
