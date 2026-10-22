package com.techgv.cineverse.domain

data class MediaRef(val mediaType: MediaType, val id: Int) { val key: String get() = "$mediaType:$id" }
