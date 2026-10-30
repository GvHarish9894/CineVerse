package com.techgv.cineverse.ui

import androidx.compose.ui.window.ComposeUIViewController
import com.techgv.cineverse.ui.mobile.MobileApplication
import com.techgv.cineverse.ui.mobile.MobileExternalActions
import com.techgv.cineverse.ui.mobile.NativeMobileApp
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

/** iOS entry: the Swift host reads the native read token from its Info.plist and passes it here. */
fun MainViewController(nativeReadToken: String?): UIViewController {
    // Swift's App initializer already supplied the token to the one native graph; never create another client here.
    val graph = KoinPlatform.getKoin().get<MobileApplication>()
    val external = KoinPlatform.getKoin().get<MobileExternalActions>()
    return ComposeUIViewController { NativeMobileApp(graph, external) }
}
