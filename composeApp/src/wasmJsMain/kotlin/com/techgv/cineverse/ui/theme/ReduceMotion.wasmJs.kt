package com.techgv.cineverse.ui.theme

import androidx.compose.runtime.Composable

@OptIn(ExperimentalWasmJsInterop::class)
private fun prefersReducedMotion(): Boolean =
    js("typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches")

@Composable
actual fun systemReduceMotion(): Boolean = prefersReducedMotion()
