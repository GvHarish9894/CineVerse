package com.techgv.cineverse.ui

import androidx.compose.ui.window.ComposeUIViewController
import com.techgv.cineverse.ui.adaptive.ShellStyle
import platform.UIKit.UIViewController

/** iOS entry: the Swift host reads the native read token from its Info.plist and passes it here. */
fun MainViewController(nativeReadToken: String?): UIViewController =
    ComposeUIViewController { CineVerseApp(ShellStyle.Mobile, nativeReadToken) }
