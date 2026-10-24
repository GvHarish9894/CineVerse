package com.techgv.cineverse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.gallery.GALLERY_ROUTE
import com.techgv.cineverse.ui.gallery.GalleryScreen
import com.techgv.cineverse.ui.shell.MobileShell
import com.techgv.cineverse.ui.shell.WebShell
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme

/**
 * Shared entry for androidApp / iosApp / wasm. [nativeReadToken] is injected by the Android/iOS entry points only
 * (wasm passes null) and is handed to the data layer from P8 on; the P7 gallery needs no network credentials.
 */
@Composable
fun CineVerseApp(shell: ShellStyle, nativeReadToken: String?) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build()
    }
    // App settings are local until L1's settings store lands (the gallery's Feel section edits them live).
    var settings by remember { mutableStateOf(AppearanceSettings()) }
    CineVerseTheme(shell, settings) {
        ProvideWindowClass {
            val navController = rememberNavController()
            val host = @Composable {
                NavHost(navController, startDestination = GALLERY_ROUTE) {
                    composable(GALLERY_ROUTE) { GalleryScreen(settings, onSettingsChange = { settings = it }) }
                }
            }
            when (shell) {
                ShellStyle.Mobile -> MobileShell(host)
                ShellStyle.Web -> WebShell(host)
            }
        }
    }
}
