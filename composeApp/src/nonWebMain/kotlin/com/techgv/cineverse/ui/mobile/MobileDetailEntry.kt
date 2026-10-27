package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.techgv.cineverse.presentation.RatePrompt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class MobileRootRateAuthority(
    val prompt: RatePrompt,
    val identity: MobileIdentity,
    val lifetime: Long,
    val entry: NavBackStackEntry,
    val context: Long,
    val generation: Long,
    val detailOwner: MobileDetailViewModel?,
    val detailAuthority: MobileDetailAuthority?,
)

/** Synchronous root revocation, independent of the next composition/Flow collection frame. */
internal class MobileDetailRootBinding(private val graph: MobileApplication) {
    private var current: NavBackStackEntry? = null
    private var detailEntry: NavBackStackEntry? = null
    private var detail: MobileDetailViewModel? = null
    private var resumed = false
    private var visible = false
    private var prompt: RatePrompt? = null
    private var lastObservedPrompt: RatePrompt? = null
    private var capturedAuthority: MobileRootRateAuthority? = null
    private var operation = 0L
    private var lifetime by mutableLongStateOf(0L)

    private fun entryVisible() = resumed && current?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
    private fun detailReadsFailed() = detailEntry === current && detail?.state?.value?.let {
        it.invalid || it.settingsFailed || it.libraryFailed || it.identityFailed
    } == true

    private fun rateAllowed() = visible && entryVisible() && current?.destination?.hasRoute<MobileTrailerRoute>() == false &&
        graph.settings.value != null && !graph.settingsFailed.value && graph.identity.value != null &&
        !graph.identityFailed.value && !graph.storageFailed.value && !detailReadsFailed()

    private fun revoke(token: Long, owner: MobileDetailViewModel? = detail) {
        visible = false
        lifetime++
        prompt = null
        capturedAuthority = null
        owner?.setActive(false)
        if (operation == token) graph.actions.closeRate()
    }

    fun leave() {
        val token = ++operation
        current = null
        revoke(token)
    }

    fun setResumed(value: Boolean) {
        val token = ++operation
        resumed = value
        if (!value) revoke(token) else reconcile(token)
    }

    fun currentChanged(entry: NavBackStackEntry?) {
        val token = ++operation
        if (current !== entry) {
            current = entry
            revoke(token)
            if (operation != token) return
        }
        graph.actions.setPlayerActive(entry?.destination?.hasRoute<MobileTrailerRoute>() == true)
        if (operation == token) reconcile(token)
    }

    fun clearCurrent(entry: NavBackStackEntry?) { if (current === entry) leave() }

    fun entryStateChanged(entry: NavBackStackEntry) { if (current === entry) reconcile(++operation) }

    private fun reconcile(token: Long) {
        if (!entryVisible()) {
            if (visible) revoke(token) else detail?.setActive(false)
            return
        }
        visible = true
        detail?.setActive(detailEntry === current)
        if (operation != token) return
        if (!rateAllowed()) { prompt = null; capturedAuthority = null; graph.actions.closeRate() }
    }

    fun attach(entry: NavBackStackEntry, model: MobileDetailViewModel) {
        val token = ++operation
        val previous = detail
        detailEntry = entry
        detail = model
        if (previous !== model) previous?.setActive(false)
        if (operation == token) reconcile(token)
    }

    fun detach(entry: NavBackStackEntry, model: MobileDetailViewModel) {
        if (detailEntry !== entry || detail !== model) return
        val token = ++operation
        detail = null
        detailEntry = null
        if (current === entry) revoke(token, model) else model.setActive(false)
    }

    fun detailReadsChanged(entry: NavBackStackEntry, model: MobileDetailViewModel) {
        if (detailEntry === entry && detail === model && detailReadsFailed()) {
            operation++
            lifetime++
            prompt = null
            capturedAuthority = null
            graph.actions.closeRate()
        }
    }

    fun contextChanged() {
        val token = ++operation
        val owner = detail
        lifetime++
        prompt = null
        capturedAuthority = null
        owner?.contextChanged()
        if (operation == token) graph.actions.closeRate()
    }

    fun observePrompt(next: RatePrompt?): RatePrompt? {
        if (next == null) {
            if (prompt != null) lifetime++
            prompt = null
            capturedAuthority = null
            return null
        }
        // A missed local failure/recovery must never rebind the same producer prompt to a new read.
        if (lastObservedPrompt === next) return next.takeIf { rateAuthority(it) != null }
        lastObservedPrompt = next
        lifetime++
        prompt = next
        val identity = graph.identity.value
        val entry = current
        val owner = detail.takeIf { detailEntry === entry }
        val read = owner?.state?.value?.authority
        capturedAuthority = if (rateAllowed() && identity != null && identity.session == next.identity && entry != null &&
            (entry.destination.hasRoute<MobileDetailRoute>() == false || (owner != null && read != null))) {
            MobileRootRateAuthority(next, identity, lifetime, entry, graph.contextChanges.value, graph.generation.current, owner, read)
        } else null
        if (capturedAuthority == null) {
            prompt = null
            graph.actions.closeRate(next)
            return null
        }
        // Fresh verified intent supersedes an older context-close continuation.
        operation++
        return next
    }

    fun rateAuthority(rendered: RatePrompt): MobileRootRateAuthority? =
        capturedAuthority?.takeIf { it.prompt === rendered && owns(it) }

    private fun owns(authority: MobileRootRateAuthority) = rateAllowed() && prompt === authority.prompt &&
        current === authority.entry && lifetime == authority.lifetime && graph.identity.value == authority.identity &&
        graph.contextChanges.value == authority.context && graph.generation.current == authority.generation &&
        detail.takeIf { detailEntry === current } === authority.detailOwner &&
        authority.detailOwner?.state?.value?.authority == authority.detailAuthority

    fun submit(authority: MobileRootRateAuthority, value: Double) {
        if (owns(authority)) graph.actions.submitRating(value, authority.prompt)
    }

    fun remove(authority: MobileRootRateAuthority) {
        if (owns(authority)) graph.actions.removeRating(authority.prompt)
    }

    fun dismiss(authority: MobileRootRateAuthority) {
        if (owns(authority)) graph.actions.closeRate(authority.prompt)
    }

    fun close() {
        val token = ++operation
        current = null
        revoke(token)
        if (operation == token) graph.actions.setPlayerActive(false)
    }
}

@Composable
internal fun rememberMobileDetailRootBinding(graph: MobileApplication, controller: NavHostController): MobileDetailRootBinding {
    val binding = remember(graph) { MobileDetailRootBinding(graph) }
    DisposableEffect(binding) { onDispose { binding.close() } }
    DisposableEffect(binding, controller) {
        var observed: NavBackStackEntry? = null
        var observer: LifecycleEventObserver? = null
        val listener = NavController.OnDestinationChangedListener { _, _, _ ->
            observer?.let { observed?.lifecycle?.removeObserver(it) }
            val next = controller.currentBackStackEntry
            observed = next
            binding.currentChanged(next)
            observer = next?.let { entry -> LifecycleEventObserver { _, _ -> binding.entryStateChanged(entry) } }
            observer?.let { next?.lifecycle?.addObserver(it) }
        }
        controller.addOnDestinationChangedListener(listener)
        onDispose {
            controller.removeOnDestinationChangedListener(listener)
            observer?.let { observed?.lifecycle?.removeObserver(it) }
            binding.clearCurrent(observed)
        }
    }
    LaunchedEffect(graph, binding) { graph.contextChanges.collect { binding.contextChanged() } }
    return binding
}

@Composable
internal fun MobileDetailRouteEntry(
    graph: MobileApplication,
    entry: NavBackStackEntry,
    binding: MobileDetailRootBinding,
    navigator: MobileNavigator,
    onBack: () -> Unit,
) {
    val route = entry.toRoute<MobileDetailRoute>()
    val model = viewModel(viewModelStoreOwner = entry) { graph.detail(route.media, route.id, createSavedStateHandle()) }
    DisposableEffect(entry, model, binding) {
        binding.attach(entry, model)
        onDispose { binding.detach(entry, model) }
    }
    LaunchedEffect(entry, model, binding) {
        model.state.map { it.invalid || it.settingsFailed || it.libraryFailed || it.identityFailed }
            .distinctUntilChanged().collect { binding.detailReadsChanged(entry, model) }
    }
    MobileDetailScreen(model, navigator, onBack)
}

@Composable
internal fun MobileRootRateHost(graph: MobileApplication, binding: MobileDetailRootBinding) {
    var prompt by remember(graph) { mutableStateOf<RatePrompt?>(null, referentialEqualityPolicy()) }
    LaunchedEffect(graph, binding) {
        graph.actions.ratePrompt.collect { prompt = binding.observePrompt(it) }
    }
    val authority = prompt?.let { binding.rateAuthority(it) } ?: return
    key(authority.lifetime) {
        // The producer closes before dispatch; later completion/failure belongs to root outcome toasts.
        MobileRateSheet(authority.prompt, pending = false, error = null,
            onSubmit = { binding.submit(authority, it) }, onRemove = { binding.remove(authority) },
            onDismiss = { binding.dismiss(authority) })
    }
}
