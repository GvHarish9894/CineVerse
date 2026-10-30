package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue

internal class PortableStateRestoration {
    private var registry = SaveableStateRegistry(restoredValues = null) { true }
    private var emit by mutableStateOf(true)

    // Same call site before and after, so rememberSaveable keys (composite key hash) are stable;
    // key(generation) would change the hash and nothing would restore.
    @Composable
    fun Content(content: @Composable () -> Unit) {
        if (emit) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { content() }
        } else {
            SideEffect { emit = true }
        }
    }

    fun emulateSaveAndRestore() {
        registry = SaveableStateRegistry(restoredValues = registry.performSave()) { true }
        emit = false
    }
}
