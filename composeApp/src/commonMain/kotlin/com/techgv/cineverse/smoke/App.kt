package com.techgv.cineverse.smoke

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory

// P0 toolchain smoke only (docs/TOOLCHAIN.md): one Navigation Compose route, a SavedStateHandle ViewModel, a Coil image.

private const val SMOKE_ROUTE = "smoke"

// Public, credential-free TMDB image host (the image client never carries the read token).
const val SMOKE_IMAGE_URL = "https://image.tmdb.org/t/p/w342/qJ2tW6WMUDux911r6m7haRef0WH.jpg"

/** [nativeReadToken] is injected by the Android/iOS entry points only; wasm always passes null. */
@Composable
fun App(nativeReadToken: String?) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build()
    }
    val navController = rememberNavController()
    NavHost(navController, startDestination = SMOKE_ROUTE) {
        composable(SMOKE_ROUTE) {
            SmokeScreen(nativeTokenConfigured = !nativeReadToken.isNullOrBlank())
        }
    }
}

@Composable
private fun SmokeScreen(nativeTokenConfigured: Boolean) {
    val viewModel = viewModel { SmokeViewModel(createSavedStateHandle()) }
    val visits by viewModel.visits.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.recordVisit() }

    val textStyle = TextStyle(color = Color.White)
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("CineVerse smoke", style = textStyle)
        BasicText("Visits (SavedStateHandle): $visits", style = textStyle)
        BasicText("Native read token: ${if (nativeTokenConfigured) "configured" else "absent"}", style = textStyle)
        AsyncImage(
            model = SMOKE_IMAGE_URL,
            contentDescription = "Smoke poster",
            modifier = Modifier.size(width = 171.dp, height = 256.dp),
            // Greppable runtime proof per platform (logcat / simulator console / browser console).
            onSuccess = { println("CineVerseSmoke image=success") },
            onError = { println("CineVerseSmoke image=error ${it.result.throwable}") },
        )
    }
}
