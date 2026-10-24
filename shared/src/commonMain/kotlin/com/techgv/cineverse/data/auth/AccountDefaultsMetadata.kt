package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.AccountObjectId

/** Verified, nonsecret TMDB defaults for one sign-in. Null fields are unknown; all null means unavailable. */
data class AccountDefaultsMetadata(
    val accountObjectId: AccountObjectId,
    val sessionGeneration: Long,
    val language: String?,
    val region: String?,
    val includeAdult: Boolean?,
)
