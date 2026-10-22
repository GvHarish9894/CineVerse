package com.techgv.cineverse.ui.components

import androidx.compose.ui.window.DialogProperties
import com.techgv.cineverse.ui.design.Scrims

actual fun cvDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, scrimColor = Scrims.DialogScrim)
