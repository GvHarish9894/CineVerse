package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue

internal class PortableStateRestoration {
    private var registry = SaveableStateRegistry(restoredValues = null) { true }
    private var generation by mutableIntStateOf(0)

    @Composable
    fun Content(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
            key(generation) { content() }
        }
    }

    fun emulateSaveAndRestore() {
        registry = SaveableStateRegistry(restoredValues = registry.performSave()) { true }
        generation++
    }
}
