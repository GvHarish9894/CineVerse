package com.techgv.cineverse.ui.components

import androidx.compose.ui.window.DialogProperties

/** Android draws the dialog scrim as the window dim (platform default amount); no per-dialog scrim colour. */
actual fun cvDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)

@androidx.compose.runtime.Composable
internal actual fun cvDialogSemantics(title: String, ownedElementId: String?): androidx.compose.ui.Modifier = androidx.compose.ui.Modifier

@androidx.compose.runtime.Composable
internal actual fun cvDialogWindow(
    onDismissRequest: () -> Unit, closeFocus: androidx.compose.ui.focus.FocusRequester,
    restoreFocus: androidx.compose.ui.focus.FocusRequester?, onRestoreFocus: (() -> Unit)?,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    val restore = androidx.compose.runtime.rememberUpdatedState(onRestoreFocus)
    androidx.compose.runtime.DisposableEffect(restoreFocus) {
        onDispose {
            restore.value?.invoke() ?: restoreFocus?.let { runCatching { it.requestFocus() } }
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = cvDialogProperties(),
        content = content,
    )
}
