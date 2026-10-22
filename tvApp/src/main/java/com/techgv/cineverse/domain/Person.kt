package com.techgv.cineverse.domain

/** A person as popular people / search / credits list them. */
data class Person(
    val id: Int,
    val name: String,
    val profilePath: String?,
    val knownForDepartment: String?,
    val knownFor: List<Title>,
)
