package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.library.LibraryStateSnapshot
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.formatRuntime
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class WebDetailEntry(val ref: MediaRef, val index: Int, val offset: Int)

/** Read raw strings, not StateSaver's forgiving numeric getters. Rejection never rewrites history. */
internal fun validateWebDetailEntry(entry: WebEntry): WebDetailEntry {
    val route = entry.route as? WebRoute.Detail ?: throw IllegalArgumentException("Not a title route")
    val media = when (route.media) {
        "movie" -> MediaType.Movie
        "tv" -> MediaType.Tv
        else -> throw IllegalArgumentException("Invalid title media")
    }
    require(route.id > 0)
    val savedMedia = entry.getString("detail.media")
    val savedId = entry.getString("detail.id")
    require((savedMedia == null) == (savedId == null))
    require(savedMedia == null || (savedMedia == route.media && savedId == route.id.toString()))
    fun scalar(key: String): Int {
        val raw = entry.getString(key) ?: return 0
        val value = raw.toIntOrNull()
        require(value != null && value >= 0 && value.toString() == raw)
        return value
    }
    return WebDetailEntry(MediaRef(media, route.id), scalar("detail.index"), scalar("detail.offset"))
}

/**
 * The opening card's title and poster, kept in the Detail entry's history scalars (never its URL) so the hero poster and
 * its shared element exist on Detail's first frame. Display-only while the detail read is loading, never a source of truth;
 * an entry without one (deep link, reload without history state, other openers) renders as before.
 */
internal class WebDetailSeed(val title: String, val posterPath: String?)

internal fun Title.webDetailSeed(): Map<String, String> = buildMap {
    put("detail.seedTitle", title)
    posterPath?.let { put("detail.seedPoster", it) }
}

internal fun WebEntry.webDetailSeed(): WebDetailSeed? = getString("detail.seedTitle")?.let { WebDetailSeed(it, getString("detail.seedPoster")) }

/**
 * A producer snapshot is actionable only when its source identity equals the rendered Ready session (full equality;
 * known null origin is guest data, so only a rendered guest). Anything else is Unknown; the snapshot is never relabelled.
 */
internal fun LibraryStateSnapshot.webDetailAccount(rendered: WebSessionState.Ready?): LibraryState =
    if (rendered != null && sourceIdentityKnown && sourceIdentity == rendered.session) state else LibraryState.Unknown

internal data class WebDetailReadState(
    val section: SectionState<Detail> = SectionState.Loading,
    val retained: Detail? = null,
    val settingsFailed: Boolean = false,
    val freshnessUnverified: Boolean = false,
    val producer: DetailUiState = DetailUiState(),
)

/** One immutable producer snapshot proves a primary completion, including a genuine cache hit, not HTTP freshness. */
internal fun DetailUiState.completedWebDetail(ref: MediaRef, appGeneration: Long): DetailReadContent? {
    val context = request ?: return null
    val completed = completion ?: return null
    if (settingsRead != DetailSettingsRead.Ready || context.ref != ref || context.appGeneration != appGeneration ||
        completed.context != context || completed.result != detail) return null
    val content = (completed.result as? SectionState.Content)?.value ?: return null
    return retained?.takeIf { it.context == context && it.detail == content && content.ref == ref }
}

/**
 * Entry-owned raw reader and catalog holder. The root MUST forward actual app/context invalidations to
 * [contextChanged]; GenerationCounter is only a snapshot. Clicks recheck it even before the event is delivered.
 * Account state comes from the root's [accountState] producer snapshots (source identity on the same payload), masked
 * by [webDetailAccount]; writes and Rate use the explicit expected-session overloads with the rendered session.
 */
internal class WebDetailOwner(
    entry: WebEntry,
    catalog: CatalogRepository,
    private val generation: GenerationCounter,
    settings: Flow<Settings>,
    private val session: StateFlow<WebSessionState>,
    private val storage: StateFlow<BrowserStorageFailure?>,
    private val retryRuntimeReads: () -> Unit,
    private val reportStorageFailure: (StorageException) -> Unit,
    private val library: LibraryActionsStateHolder?,
    accountState: (MediaRef) -> Flow<LibraryStateSnapshot>,
    parentScope: CoroutineScope,
) {
    val validated = validateWebDetailEntry(entry) // Before scope, holder, subscriptions or fetch.
    private val job = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(WebDetailReadState())
    val state = mutableState.asStateFlow()
    private var closed = false
    private var revision = 0L
    private var readsAllowed = storage.value == null
    private var storageBlocked = storage.value != null
    private var settingsFailed = false
    private val accountReads = MutableStateFlow(0L)
    private val mutableAccount = MutableStateFlow(LibraryStateSnapshot(LibraryState.Unknown))
    val account = mutableAccount.asStateFlow()
    // No replay/default: an already blocked or exited entry must not start the holder's immediate reader.
    private val rawSettings = flow {
        if (active() && readsAllowed && storage.value == null) settings.collect { actual ->
            if (active() && readsAllowed && storage.value == null) emit(actual)
        }
    }
    val holder = DetailStateHolder(validated.ref, catalog, rawSettings, generation, library, scope) { failure ->
        if (active()) {
            settingsFailed = true
            reportStorageFailure(failure)
        }
    }

    init {
        if (storageBlocked) holder.cancelReads()
        job.invokeOnCompletion { holder.cancelReads() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.state.collect { publish(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            storage.collect { current ->
                if (current != null && !storageBlocked) {
                    storageBlocked = true
                    revokeReads()
                } else if (current == null && storageBlocked) {
                    storageBlocked = false
                    contextChanged()
                }
            }
        }
        scope.launch {
            accountReads.collectLatest { read ->
                accountState(validated.ref).catch { failure ->
                    if (failure !is StorageException) throw failure
                    if (active()) reportStorageFailure(failure)
                    emit(LibraryStateSnapshot(LibraryState.Unknown))
                }.collect { if (active() && read == accountReads.value) mutableAccount.value = it }
            }
        }
    }

    private fun active(): Boolean = !closed && job.isActive
    private fun current(token: Long): Boolean = active() && revision == token

    private fun publish(value: DetailUiState) {
        if (!active()) return
        if (value.settingsRead == DetailSettingsRead.Failed) settingsFailed = true
        else if (value.settingsRead == DetailSettingsRead.Ready) settingsFailed = false
        val completed = if (readsAllowed && storage.value == null) value.completedWebDetail(validated.ref, generation.current) else null
        val retained = value.retained?.takeIf { it.context.ref == validated.ref && it.detail.ref == validated.ref }?.detail
        val wrongRef = (value.detail as? SectionState.Content)?.value?.ref?.let { it != validated.ref } == true
        val section = when {
            wrongRef -> SectionState.Error(SectionErrorKind.Generic)
            completed != null -> SectionState.Content(completed.detail)
            value.detail is SectionState.Error -> value.detail
            else -> SectionState.Loading
        }
        mutableState.value = WebDetailReadState(section, retained, settingsFailed,
            completed == null && (retained != null || settingsFailed), value)
    }

    /** Logical revocation precedes cancellation and any callback that may synchronously close/reenter. */
    private fun revokeReads(): Long {
        val token = ++revision
        readsAllowed = false
        holder.cancelReads()
        if (current(token)) publish(holder.state.value)
        return token
    }

    private fun restartReader(token: Long) {
        if (!current(token) || storage.value != null) return
        readsAllowed = true
        holder.retryStorage() // The ACTUAL raw Flow is re-collected, even for equal preferences.
        if (current(token)) publish(holder.state.value)
    }

    fun retryReads() {
        if (!active()) return
        val token = revokeReads()
        if (!current(token)) return
        retryRuntimeReads() // Restart real upstream/runtime too; never replay a mutation.
        if (!current(token) || storage.value != null) return
        storageBlocked = false // Avoid a duplicate restart from a queued runtime-clear notification.
        accountReads.value++ // Explicit retry only: a failed account read is never replayed automatically.
        restartReader(token)
    }

    /** Required root event forwarding for idle app-generation/context invalidations; no polling. */
    fun contextChanged() {
        if (!active()) return
        val token = revokeReads()
        restartReader(token)
    }

    fun retryDetail() {
        if (active() && readsAllowed && storage.value == null) holder.retry()
    }

    fun act(kind: LibraryKind, renderedContext: DetailReadContext?, renderedSession: WebSessionState.Ready?) {
        if (!active() || !readsAllowed || storage.value != null || renderedSession == null || session.value != renderedSession) return
        val actions = library ?: return
        val completed = holder.state.value.completedWebDetail(validated.ref, generation.current) ?: return
        if (completed.context != renderedContext || !account.value.webDetailAccount(renderedSession).canToggle(kind)) return
        val title = completed.detail.toTitle()
        val expected = renderedSession.session
        when (kind) {
            LibraryKind.WATCHLIST -> actions.toggleWatchlist(title, expected)
            LibraryKind.FAVOURITE -> actions.toggleFavourite(title, expected)
            LibraryKind.RATING -> actions.openRate(title, expected)
        }
    }

    /** Retained public IDs/trailers remain browsable, but never from a disposed entry callback. */
    fun browse(action: () -> Unit) {
        if (active()) action()
    }

    fun close() {
        if (closed) return
        closed = true
        revokeReads()
        job.cancel()
    }
}

internal fun Detail.webDetailVideos(): List<Video> = videos.filter { it.key.matches(Regex("[A-Za-z0-9_-]{11}")) }.distinctBy { it.key }
internal fun Detail.webDetailTagline(): String? = tagline.takeIf { it.isNotBlank() }?.let { it + if (taglineInEnglish) " (English)" else "" }
internal fun Detail.webDetailOverview(): String? = overview.takeIf { it.isNotBlank() }?.let { it + if (overviewInEnglish) " (English)" else "" }
internal fun Detail.webDetailMetadata(): String = listOfNotNull(
    year, certification?.takeIf { it.isNotBlank() }, releaseDate?.takeIf { it.isNotBlank() },
    genres.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() },
    runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime),
    network?.takeIf { it.isNotBlank() },
).joinToString(" · ")
