package com.techgv.cineverse.domain

/** A YouTube video of a title; [key] is the YouTube video id. */
data class Video(val key: String, val name: String, val type: String, val official: Boolean)
