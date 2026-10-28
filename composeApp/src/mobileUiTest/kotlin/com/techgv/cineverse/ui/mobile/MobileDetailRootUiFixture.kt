package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.*
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.presentation.RatePrompt
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.shell.MobileShell
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

@Composable
internal fun MobileDetailRootUiFixture(
    knownGuest: Boolean = true,
    actionObserver: ((MobileDetailViewModel, MobileDetailAuthority?, (LibraryKind) -> Unit) -> Unit)? = null,
    onReady: (MobileDetailRootFixture, MobileDetailRootUiOwner) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fixture = remember { MobileDetailRootFixture(scope) }
    val owner = remember { MobileDetailRootUiOwner() }
    val home = remember { MobileHomeViewModel(fixture.graph, SavedStateHandle(), scope) }
    LaunchedEffect(Unit) { if (knownGuest) fixture.auth.emissions.emit(null) }
    SideEffect { onReady(fixture, owner) }
    DisposableEffect(Unit) { onDispose { owner.viewModelStore.clear(); fixture.graph.close() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner,
        LocalMobileDetailActionObserver provides actionObserver) {
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
            ProvideWindowClass { MobileShell { MobileRoot(fixture.graph, home) } }
        }
    }
}

/** Two real resumed entries; root status collection is deliberately controlled by each regression. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun MobileDetailRootBindingUiFixture(
    scheduler: TestCoroutineScheduler? = null,
    onReady: (MobileDetailRootBindingUiState) -> Unit,
) {
    val compositionScope = rememberCoroutineScope()
    // Exercise synchronous publication/cancellation reentry, not a later UI frame.
    val scope = remember { CoroutineScope(compositionScope.coroutineContext + Dispatchers.Unconfined) }
    val owner = remember { MobileDetailRootUiOwner() }
    val first = rememberNavController()
    val second = rememberNavController()
    val entryA by first.currentBackStackEntryAsState()
    val entryB by second.currentBackStackEntryAsState()
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
        Column {
            NavHost(first, startDestination = MobileDetailRoute("movie", 101)) {
                composable<MobileDetailRoute> { Text("Owner A") }
            }
            NavHost(second, startDestination = MobileDetailRoute("movie", 765)) {
                composable<MobileDetailRoute> { Text("Owner B") }
            }
        }
    }
    val a = entryA
    val b = entryB
    if (a != null && b != null) {
        val workScope = remember(a, b) {
            if (scheduler == null) scope else CoroutineScope(compositionScope.coroutineContext +
                Job(compositionScope.coroutineContext[Job]) + StandardTestDispatcher(scheduler))
        }
        val fixture = remember(a, b) { MobileDetailRootFixture(workScope) }
        val state = remember(a, b) { MobileDetailRootBindingUiState(fixture, scope, workScope, a, b) }
        DisposableEffect(state) {
            onDispose {
                state.close()
                owner.viewModelStore.clear()
                fixture.graph.close()
                if (scheduler != null) {
                    workScope.cancel()
                    scheduler.runCurrent()
                }
            }
        }
        SideEffect { onReady(state) }
    }
}

internal class MobileDetailRootBindingUiState(
    val fixture: MobileDetailRootFixture,
    val scope: CoroutineScope,
    workScope: CoroutineScope,
    val entryA: NavBackStackEntry,
    val entryB: NavBackStackEntry,
) {
    val binding = MobileDetailRootBinding(fixture.graph)
    val modelA = fixture.graph.detail("movie", 101, SavedStateHandle(), workScope)
    val modelB = fixture.graph.detail("movie", 765, SavedStateHandle(), workScope)
    var latestPrompt: RatePrompt? = null
    val observedPrompts = mutableListOf<RatePrompt?>()
    private val prompts = workScope.launch {
        fixture.graph.actions.ratePrompt.collect {
            observedPrompts += it
            latestPrompt = it
            binding.observePrompt(it)
        }
    }

    fun activateA() {
        fixture.auth.emissions.tryEmit(null)
        binding.setResumed(true)
        binding.currentChanged(entryA)
        binding.attach(entryA, modelA)
    }

    fun activateB() {
        binding.currentChanged(entryB)
        binding.attach(entryB, modelB)
    }

    fun close() {
        prompts.cancel()
        binding.close()
        modelA.close()
        modelB.close()
    }
}

internal class MobileDetailRootUiOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
