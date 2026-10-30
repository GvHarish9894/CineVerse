package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.library.LibraryStateSnapshot
import com.techgv.cineverse.domain.MediaRef
import kotlinx.coroutines.flow.Flow
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme

@Composable
internal fun MobileDetailUiFixture(
    media: String = "movie",
    savedState: SavedStateHandle = SavedStateHandle(),
    knownGuest: Boolean = true,
    configure: (MobileDetailComponentFixture) -> Unit = {},
    onReady: (MobileDetailViewModel, MobileDetailComponentFixture) -> Unit = { _, _ -> },
    onNavigate: (MobileDestination) -> Unit = {},
    onBack: () -> Unit = {},
    onShare: ((MediaRef) -> Unit)? = null,
    libraryState: (MobileDetailComponentFixture) -> Flow<LibraryStateSnapshot> = { it.accountState },
) {
    val scope = rememberCoroutineScope()
    val f = remember { MobileDetailComponentFixture(scope).also { if (knownGuest) it.guest(); configure(it) } }
    val model = remember { MobileDetailViewModel(f.graph, media, 42, savedState, f.rawSettings, libraryState(f), scope) }
    DisposableEffect(model) { model.setActive(true); onDispose { model.close(); f.graph.close() } }
    SideEffect { onReady(model, f) }
    CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
        ProvideWindowClass { MobileDetailScreen(model, MobileNavigator(onNavigate), onBack, onShare) }
    }
}
