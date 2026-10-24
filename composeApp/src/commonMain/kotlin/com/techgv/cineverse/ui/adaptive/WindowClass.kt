package com.techgv.cineverse.ui.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width buckets shared by both shells: phone ≤ 640, tablet 641–999, desktop ≥ 1000 (web kit breakpoints). */
enum class WindowClass {
    Phone, Tablet, Desktop;

    companion object {
        fun fromWidth(width: Dp): WindowClass = when {
            width <= 640.dp -> Phone
            width < 1000.dp -> Tablet
            else -> Desktop
        }
    }
}

val LocalWindowClass = staticCompositionLocalOf { WindowClass.Phone }

/**
 * Which kit's sizes a primitive uses. The shell decides, not the width: narrow web keeps web chrome and web
 * sizes except where the web kit's own ≤ 640 rules apply (plan §4.3).
 */
enum class ShellStyle { Mobile, Web }

val LocalShellStyle = staticCompositionLocalOf { ShellStyle.Mobile }

/** Measures the available width once and provides [LocalWindowClass] to [content]. */
@Composable
fun ProvideWindowClass(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        CompositionLocalProvider(LocalWindowClass provides WindowClass.fromWidth(maxWidth)) { content() }
    }
}
