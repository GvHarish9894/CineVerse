package com.techgv.cineverse.ui.mobile

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.ui.theme.LocalReduceMotion

/** Shared-element keys are the exact media identity, so any card and the Detail header for one title match. */
internal fun mobilePosterSharedKey(ref: MediaRef) = "poster:${ref.key}"
internal fun mobileTitleSharedKey(ref: MediaRef) = "title:${ref.key}"

private val LocalMobileSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalMobileRouteVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Wraps the tab NavHost so its destinations can share elements. */
@Composable
internal fun MobileSharedTransitionRoot(content: @Composable () -> Unit) = SharedTransitionLayout {
    CompositionLocalProvider(LocalMobileSharedTransitionScope provides this, content = content)
}

/** Provides a NavHost destination's own enter/exit scope to the shared elements inside it. */
@Composable
internal fun MobileSharedRoute(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalMobileRouteVisibilityScope provides scope, content = content)

/** Poster art: card ↔ Detail header. */
@Composable
internal fun mobileSharedPoster(ref: MediaRef): Modifier = mobileShared(mobilePosterSharedKey(ref), bounds = false)

/** Title text: bounds shared and contents crossfaded, since the card and header set it at different sizes. */
@Composable
internal fun mobileSharedTitle(ref: MediaRef): Modifier = mobileShared(mobileTitleSharedKey(ref), bounds = true)

/** No modifier (a plain cut) under Reduce motion or outside a shared root/route. */
@Composable
private fun mobileShared(key: String, bounds: Boolean): Modifier {
    val transition = LocalMobileSharedTransitionScope.current
    val route = LocalMobileRouteVisibilityScope.current
    if (transition == null || route == null || LocalReduceMotion.current) return Modifier
    return with(transition) {
        val state = rememberSharedContentState(key)
        if (bounds) Modifier.sharedBounds(state, route) else Modifier.sharedElement(state, route)
    }
}
