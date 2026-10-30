package com.techgv.cineverse.ui.mobile

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun MobileRootBackHandler(enabled: Boolean, onBack: () -> Unit) {
    PredictiveBackHandler(enabled) { progress ->
        progress.collect { }
        onBack()
    }
}
