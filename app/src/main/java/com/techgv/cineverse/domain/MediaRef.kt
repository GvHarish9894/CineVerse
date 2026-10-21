package com.techgv.cineverse.domain

/** Identity of a title (§1): focus memory and library keys use [key], never an index. */
data class MediaRef(val mediaType: MediaType, val id: Int) {
    val key: String get() = "$mediaType:$id"
}
