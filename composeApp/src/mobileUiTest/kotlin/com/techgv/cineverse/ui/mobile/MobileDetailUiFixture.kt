package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme

@Composable
internal fun MobileDetailUiFixture(
    media: String = "movie",
    configure: (MobileDetailComponentFixture) -> Unit = {},
    onReady: (MobileDetailViewModel, MobileDetailComponentFixture) -> Unit = { _, _ -> },
    onNavigate: (MobileDestination) -> Unit = {},
    onBack: () -> Unit = {},
    onShare: ((MediaRef) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val f = remember { MobileDetailComponentFixture(scope).also { it.guest(); configure(it) } }
    val model = remember { MobileDetailViewModel(f.graph, media, 42, SavedStateHandle(), f.rawSettings, f.accountState, scope) }
    DisposableEffect(model) { model.setActive(true); onDispose { model.close(); f.graph.close() } }
    SideEffect { onReady(model, f) }
    CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
        ProvideWindowClass { MobileDetailScreen(model, MobileNavigator(onNavigate), onBack, onShare) }
    }
}
