package com.techgv.cineverse.di

import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import kotlin.reflect.KClass

/** Resolves every registered definition (except [skip]) and returns their types: a failing wiring throws here. */
@OptIn(KoinInternalApi::class)
fun Koin.resolveEveryDefinition(skip: Set<KClass<*>> = emptySet()): List<KClass<*>> =
    instanceRegistry.instances.values.map { it.beanDefinition }.distinct()
        .filter { it.primaryType !in skip }
        .onEach { get<Any>(it.primaryType, it.qualifier) }
        .map { it.primaryType }
