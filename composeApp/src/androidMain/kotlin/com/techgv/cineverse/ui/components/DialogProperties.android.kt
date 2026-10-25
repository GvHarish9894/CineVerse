package com.techgv.cineverse.ui.components

import androidx.compose.ui.window.DialogProperties

/** Android draws the dialog scrim as the window dim (platform default amount); no per-dialog scrim colour. */
actual fun cvDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
