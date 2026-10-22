package com.techgv.cineverse.domain

/** A person credited on a title (Top billed cast, Director / Creator, guest stars); [role] is display-ready. */
data class CastCredit(val personId: Int, val name: String, val profilePath: String?, val role: String)

/**
 * A title in a person's credits. [date] is the ISO release / first-air date (null when blank); [role] is
 * the sub-line: `as {character}` / `{job}` / `{n} episodes`, or empty.
 */
data class PersonCredit(
    val ref: MediaRef,
    val title: String,
    val date: String?,
    val posterPath: String?,
    val role: String,
)
