package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.domain.MediaRef
import org.koin.core.scope.Scope

/** Platform hand-off for content CineVerse does not play or host itself. False means the platform did not take it. */
interface MobileExternalActions {
    /** YouTube app deep link first, then the HTTPS watch page. */
    suspend fun openYouTube(key: String): Boolean
    suspend fun openUrl(url: String): Boolean
    fun share(text: String): Boolean
}

/** The bound platform adapter; UI tests supply fakes instead. */
internal expect fun Scope.mobileExternalActions(): MobileExternalActions

private val YouTubeKey = Regex("[A-Za-z0-9_-]{11}")

/** The only accepted trailer key shape: route scalars and TMDB data are never trusted as URL fragments. */
internal fun isYouTubeKey(key: String): Boolean = YouTubeKey.matches(key)

internal fun youTubeWatchUrl(key: String): String = "https://www.youtube.com/watch?v=$key"

internal fun MediaRef.tmdbUrl(): String = "https://www.themoviedb.org/${mediaType.path}/$id"
