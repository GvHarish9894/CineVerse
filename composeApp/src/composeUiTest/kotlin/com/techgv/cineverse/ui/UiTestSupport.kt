package com.techgv.cineverse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.LocalToasts
import com.techgv.cineverse.ui.components.ToastState
import com.techgv.cineverse.ui.theme.CineVerseTheme

/** Content inside the real theme for [shell] (fonts, locals) with a toast state, like the shells provide. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setThemedContent(shell: ShellStyle = ShellStyle.Mobile, content: @Composable () -> Unit) {
    setContent {
        CineVerseTheme(shell) {
            CompositionLocalProvider(LocalToasts provides ToastState(), content = content)
        }
    }
}
