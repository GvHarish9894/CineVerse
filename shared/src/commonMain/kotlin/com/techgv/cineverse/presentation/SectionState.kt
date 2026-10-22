package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException

/** Per-section async state (§1.1): a failed row never blanks the screen. */
sealed interface SectionState<out T> {
    data object Loading : SectionState<Nothing>
    data class Content<T>(val value: T) : SectionState<T>
    data object Empty : SectionState<Nothing>
    data class Error(val kind: SectionErrorKind) : SectionState<Nothing>
}

/** Why a section failed; each shell owns the copy. [retryable]: the section offers "Try again". */
enum class SectionErrorKind(val retryable: Boolean) {
    /** Network failure: distinct from empty (§3.4 offline state). */
    Offline(true),
    NotAvailable(false),
    AppCredential(false),
    /** The app is already signing out and returning to Login (AuthRepository.sessionExpired). */
    SessionExpired(false),
    Generic(true),
}

fun Throwable.toSectionErrorKind(): SectionErrorKind = when ((this as? TmdbException)?.error) {
    TmdbError.Network -> SectionErrorKind.Offline
    TmdbError.NotFound -> SectionErrorKind.NotAvailable
    TmdbError.AppCredential -> SectionErrorKind.AppCredential
    TmdbError.SessionExpired -> SectionErrorKind.SessionExpired
    else -> SectionErrorKind.Generic
}
