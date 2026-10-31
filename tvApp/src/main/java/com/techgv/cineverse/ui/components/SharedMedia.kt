package com.techgv.cineverse.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.presentation.HomeCard

/** Shared-element key of a title's poster: the exact media identity, so a Home card and the Detail hero match. */
fun sharedPosterKey(ref: MediaRef) = "poster:${ref.key}"

/** Shared-element key of a title's backdrop (Landscape cards, Detail backdrop). */
fun sharedBackdropKey(ref: MediaRef) = "backdrop:${ref.key}"

/** The image a Home card shows is the one it shares: Landscape → backdrop, Poster → poster. */
fun homeCardSharedKey(ref: MediaRef, card: HomeCard) =
    if (card == HomeCard.Landscape) sharedBackdropKey(ref) else sharedPosterKey(ref)

private val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalSharedMediaVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Provided once around the NavHost by its `SharedTransitionLayout`. */
@Composable
fun ProvideSharedTransition(scope: SharedTransitionScope, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalSharedTransition provides scope, content = content)

/** Provided by a destination whose artwork takes part (Home, Detail) with its NavHost `AnimatedContentScope`. */
@Composable
fun ProvideSharedMediaVisibility(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalSharedMediaVisibility provides scope, content = content)

/**
 * `sharedElement` for [key] between Home and Detail; a no-op outside those destinations and with Reduce motion
 * (then the artwork cuts with its screen, no bounds animation). Place it before any focus-scale `graphicsLayer`
 * so the shared bounds are the unscaled layout bounds. [renderInOverlay] false keeps an element drawn behind
 * its screen's content (Detail backdrop) instead of above it.
 */
@Suppress("ModifierFactoryExtensionFunction") // remember-style: it must call rememberSharedContentState in composition
@Composable
fun rememberSharedMedia(key: String, renderInOverlay: Boolean = true): Modifier {
    val transition = LocalSharedTransition.current
    val visibility = LocalSharedMediaVisibility.current
    if (transition == null || visibility == null || LocalSettings.current.motion) return Modifier
    return with(transition) {
        Modifier.sharedElement(rememberSharedContentState(key), visibility, renderInOverlayDuringTransition = renderInOverlay)
    }
}
