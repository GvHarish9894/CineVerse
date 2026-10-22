package com.techgv.cineverse.domain

/** A title or a person: Home's hero (§7.2) and an item of a mixed list (Search results, §7.7). */
sealed interface Hero {
    data class OfTitle(val title: Title) : Hero
    data class OfPerson(val person: Person) : Hero
}

/** Stable focus / list key (§1.3): `movie:603`, `tv:1399`, `person:287`. */
val Hero.key: String
    get() = when (this) {
        is Hero.OfTitle -> title.ref.key
        is Hero.OfPerson -> "person:${person.id}"
    }
