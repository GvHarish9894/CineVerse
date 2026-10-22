package com.techgv.cineverse.smoke

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** iOS entry: the Swift host reads the native read token from its Info.plist and passes it here. */
fun MainViewController(nativeReadToken: String?): UIViewController =
    ComposeUIViewController { App(nativeReadToken) }
