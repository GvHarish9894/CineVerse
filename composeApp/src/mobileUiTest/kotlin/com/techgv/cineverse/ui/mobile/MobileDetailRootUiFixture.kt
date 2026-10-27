package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.shell.MobileShell
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme

@Composable
internal fun MobileDetailRootUiFixture(
    knownGuest: Boolean = true,
    onReady: (MobileDetailRootFixture, MobileDetailRootUiOwner) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fixture = remember { MobileDetailRootFixture(scope) }
    val owner = remember { MobileDetailRootUiOwner() }
    val home = remember { MobileHomeViewModel(fixture.graph, SavedStateHandle(), scope) }
    LaunchedEffect(Unit) { if (knownGuest) fixture.auth.emissions.emit(null) }
    SideEffect { onReady(fixture, owner) }
    DisposableEffect(Unit) { onDispose { owner.viewModelStore.clear(); fixture.graph.close() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
            ProvideWindowClass { MobileShell { MobileRoot(fixture.graph, home) } }
        }
    }
}

internal class MobileDetailRootUiOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
