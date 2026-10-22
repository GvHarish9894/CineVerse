package com.techgv.cineverse.util

import kotlinx.atomicfu.atomic

/**
 * Request generation (§1.1): bumped on every replacement / cancellation of a scope's work. A result
 * may be committed (UI or cache) only if the generation it started with is still [current].
 */
class GenerationCounter {
    private val value = atomic(0L)
    val current: Long get() = value.value
    fun bump(): Long = value.incrementAndGet()
    fun isCurrent(generation: Long) = generation == value.value
}
