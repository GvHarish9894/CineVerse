package com.techgv.cineverse.domain

/** `androidx.annotation.Keep` on Android (R8), nothing elsewhere: that annotation is JVM-only. */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.CLASS)
expect annotation class AndroidKeep()
