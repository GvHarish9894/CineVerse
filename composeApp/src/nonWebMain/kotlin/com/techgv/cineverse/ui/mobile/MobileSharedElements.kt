package com.techgv.cineverse.ui.mobile

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.ui.theme.LocalReduceMotion

/**
 * Keys are per card (section + title), never per title alone: one title can sit in several rows at once (a Home row and
 * the Top 10), and a shared key would let any of them claim the flight. Detail adopts the tapped card's key.
 */
internal fun mobilePosterSharedKey(originId: String) = "poster:$originId"
internal fun mobileTitleSharedKey(originId: String) = "title:$originId"
internal fun mobileBackdropSharedKey(originId: String) = "backdrop:$originId"

/** The card a Detail transition flies from, set by the card's click just before it navigates. */
@Stable
internal class MobileSharedOrigin {
    var active by mutableStateOf<String?>(null)
}

/** A card's origin id: its section (row/screen) plus the title, so duplicates in other sections never match. */
internal fun mobileSharedOriginId(section: String, ref: MediaRef) = mobileSharedOriginId(section, ref.key)
internal fun mobileSharedOriginId(section: String, identity: String) = "$section:$identity"

/** A person's identity in origin ids; distinct from MediaRef keys ("movie:7", "tv:7"). */
internal fun mobilePersonIdentity(id: Int) = "person:$id"
internal fun mobileAvatarSharedKey(originId: String) = "avatar:$originId"

private val LocalMobileSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalMobileRouteVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
private val LocalMobileSharedOrigin = staticCompositionLocalOf<MobileSharedOrigin?> { null }

/** Wraps the tab NavHost so its destinations can share elements. */
@Composable
internal fun MobileSharedTransitionRoot(origin: MobileSharedOrigin = remember { MobileSharedOrigin() }, content: @Composable () -> Unit) =
    SharedTransitionLayout {
        CompositionLocalProvider(LocalMobileSharedTransitionScope provides this, LocalMobileSharedOrigin provides origin, content = content)
    }

/** Call from a card's click, before navigating: marks that card as the transition's source for open and back. */
@Composable
internal fun rememberMobileSharedOpen(): (section: String, ref: MediaRef) -> Unit {
    val origin = LocalMobileSharedOrigin.current
    return remember(origin) { { section, ref -> origin?.active = mobileSharedOriginId(section, ref) } }
}

/** [rememberMobileSharedOpen] for a person card (cast circle/row). */
@Composable
internal fun rememberMobileSharedOpenPerson(): (section: String, personId: Int) -> Unit {
    val origin = LocalMobileSharedOrigin.current
    return remember(origin) { { section, id -> origin?.active = mobileSharedOriginId(section, mobilePersonIdentity(id)) } }
}

/** Provides a NavHost destination's own enter/exit scope to the shared elements inside it. */
@Composable
internal fun MobileSharedRoute(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalMobileRouteVisibilityScope provides scope, content = content)

/**
 * The origin a Detail for [ref] pairs with: the tapped card if it showed this title, else none (a plain fade, e.g. a
 * Detail opened from another Detail). Captured once, so back returns to that same card even after later taps.
 */
@Composable
internal fun rememberMobileDetailOrigin(ref: MediaRef): String = rememberMobileSharedTarget(ref.key)

/** [rememberMobileDetailOrigin] for the Person screen. */
@Composable
internal fun rememberMobilePersonOrigin(id: Int): String = rememberMobileSharedTarget(mobilePersonIdentity(id))

@Composable
private fun rememberMobileSharedTarget(identity: String): String {
    val active = LocalMobileSharedOrigin.current?.active
    return rememberSaveable(identity) { active?.takeIf { it.endsWith(":$identity") } ?: "target-only:$identity" }
}

/** Profile photo: cast circle/row ↔ Person header. [originId] is the card's id or [rememberMobilePersonOrigin]. */
@Composable
internal fun mobileSharedAvatar(originId: String): Modifier = mobileShared(mobileAvatarSharedKey(originId), bounds = false)

@Composable
internal fun mobileSharedAvatar(personId: Int, section: String): Modifier =
    mobileSharedAvatar(mobileSharedOriginId(section, mobilePersonIdentity(personId)))

/** Poster art: card ↔ Detail header. [originId] is the card's own id, or the Detail's [rememberMobileDetailOrigin]. */
@Composable
internal fun mobileSharedPoster(originId: String): Modifier = mobileShared(mobilePosterSharedKey(originId), bounds = false)

/** Title text: bounds shared and contents crossfaded, since the card and header set it at different sizes. */
@Composable
internal fun mobileSharedTitle(originId: String): Modifier = mobileShared(mobileTitleSharedKey(originId), bounds = true)

/** Landscape art ↔ Detail backdrop: bounds shared and contents crossfaded (the card is rounded and much smaller). */
@Composable
internal fun mobileSharedBackdrop(originId: String): Modifier = mobileShared(mobileBackdropSharedKey(originId), bounds = true)

@Composable
internal fun mobileSharedBackdrop(ref: MediaRef, section: String): Modifier = mobileSharedBackdrop(mobileSharedOriginId(section, ref))

/** Card helpers: [section] names the row/screen so duplicates of one title elsewhere never match. */
@Composable
internal fun mobileSharedPoster(ref: MediaRef, section: String): Modifier = mobileSharedPoster(mobileSharedOriginId(section, ref))

@Composable
internal fun mobileSharedTitle(ref: MediaRef, section: String): Modifier = mobileSharedTitle(mobileSharedOriginId(section, ref))

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
