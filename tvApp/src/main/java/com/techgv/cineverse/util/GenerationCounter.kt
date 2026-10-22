package com.techgv.cineverse.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Request generation (§1.1): bumped on every replacement / cancellation of a scope's work. A result
 * may be committed (UI or cache) only if the generation it started with is still [current].
 */
class GenerationCounter {
    private val value = AtomicLong()
    val current: Long get() = value.get()
    fun bump(): Long = value.incrementAndGet()
    fun isCurrent(generation: Long) = generation == value.get()
}
