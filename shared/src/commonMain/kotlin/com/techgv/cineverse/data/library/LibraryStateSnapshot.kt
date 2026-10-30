package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.domain.LibraryState

/** Transient per-title read result; default/manual states carry no source proof. */
data class LibraryStateSnapshot(
    val state: LibraryState,
    /** True only after the producer supplies an origin; independent of [LibraryState.known]. */
    val sourceIdentityKnown: Boolean = false,
    /** Full token-free source identity; known null means guest data, not verified web guest authority. */
    val sourceIdentity: AuthSession? = null,
)
