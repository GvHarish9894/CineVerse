package com.techgv.cineverse.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val SPLASH_DURATION_MILLIS = 2_300L

/**
 * §7.1: starts configuration, genres and the credential load in parallel and leaves after 2.3 s
 * regardless of network. Config / genres warm the cache in the app scope and may finish later.
 */
@Composable
fun SplashScreen(container: AppContainer, onFinished: (signedIn: Boolean) -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        with(container) {
            appScope.launch { runCatching { tmdbRepository.configuration() } }
            appScope.launch { runCatching { tmdbRepository.genres(MediaType.Movie) } }
            appScope.launch { runCatching { tmdbRepository.genres(MediaType.Tv) } }
        }
        val signedIn = async { runCatching { container.authRepository.credentials.first() != null }.getOrDefault(false) }
        launch { progress.animateTo(1f, tween(SPLASH_DURATION_MILLIS.toInt(), easing = LinearEasing)) }
        delay(SPLASH_DURATION_MILLIS)
        onFinished(signedIn.await())
    }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CineVerse", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
        Box(
            Modifier.width(320.dp).height(4.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxWidth(progress.value).height(4.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}
