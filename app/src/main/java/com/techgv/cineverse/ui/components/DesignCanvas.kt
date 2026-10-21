package com.techgv.cineverse.ui.components

import androidx.compose.runtime.Composable

/**
 * shortcut: lane-4 passthrough for Lane 1's §6 design-unit canvas (1280-wide `LocalDensity`); Lane 1's
 * implementation replaces this file at merge. Dialog / Popup content must be wrapped in it.
 */
@Composable
fun DesignCanvas(content: @Composable () -> Unit) = content()
