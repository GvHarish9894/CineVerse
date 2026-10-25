package com.techgv.cineverse.ui.web

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

internal data class WebDetailReadState(
    val section: SectionState<Detail> = SectionState.Loading,
    val retained: Detail? = null,
    val settingsFailed: Boolean = false,
    val freshnessUnverified: Boolean = false,
)

/**
 * One entry-owned shared holder, plus a narrow restartable raw-settings reader. No default Settings.
 * INTEGRATION BLOCKER: the shared holder exposes neither request-context/completion nor load cancellation.
 * After a read failure this owner freezes its last real presentation, even after retry/equal-value recovery.
 * A settings emission or equal DTO is not proof of refreshed detail provenance. The shared app-generation
 * guard is also a load-time snapshot, not continuous invalidation of already published content.
 */
internal class WebDetailOwner(
    entry: WebEntry,
    catalog: CatalogRepository,
    generation: GenerationCounter,
    private val settings: Flow<Settings>,
    private val session: StateFlow<WebSessionState>,
    private val storage: StateFlow<BrowserStorageFailure?>,
    private val retryRuntimeReads: () -> Unit,
    private val reportStorageFailure: (StorageException) -> Unit,
    private val library: LibraryActions?,
    parentScope: CoroutineScope,
) {
    val validated = validateWebDetailEntry(entry) // Before scope, holder, subscriptions or fetch.
    private val job = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val holderSettings = MutableSharedFlow<Settings>(replay = 1)
    val holder = DetailStateHolder(validated.ref, catalog, holderSettings, generation, library, scope)
    private val mutableState = MutableStateFlow(WebDetailReadState())
    val state = mutableState.asStateFlow()
    private var reader: Job? = null
    private var lastSettings: Settings? = null
    private var closed = false

    init {
        scope.launch {
            holder.state.collect { value ->
                if (!mutableState.value.freshnessUnverified) mutableState.update {
                    val content = (value.detail as? SectionState.Content)?.value
                    if (content != null && content.ref != validated.ref) it.copy(section = SectionState.Error(SectionErrorKind.Generic))
                    else it.copy(section = value.detail, retained = content ?: it.retained)
                }
            }
        }
        scope.launch {
            var previous = storage.value
            storage.collect { current ->
                val recovered = previous != null && current == null
                previous = current
                if (recovered && mutableState.value.freshnessUnverified) subscribeSettings(reload = true)
            }
        }
        subscribeSettings()
    }

    private fun subscribeSettings(reload: Boolean = false) {
        if (closed) return
        reader?.cancel()
        reader = scope.launch {
            var recoveryPending = reload
            try {
                settings.collect { actual ->
                    ensureActive()
                    val previous = lastSettings
                    holderSettings.emit(actual)
                    // distinctUntilChanged inside the shared holder suppresses equal language/region emissions.
                    if (recoveryPending && previous != null &&
                        previous.language == actual.language && previous.region == actual.region) holder.retry()
                    recoveryPending = false
                    lastSettings = actual
                    mutableState.update { it.copy(settingsFailed = false) }
                }
            } catch (failure: StorageException) {
                mutableState.update { it.copy(settingsFailed = true, freshnessUnverified = true) }
                reportStorageFailure(failure)
            }
        }
    }

    fun retryReads() {
        if (closed) return
        retryRuntimeReads() // Explicitly restart both upstream and this owner's failed reader; no mutation replay.
        subscribeSettings(reload = true)
    }

    fun retryDetail() {
        if (!closed && !state.value.freshnessUnverified) holder.retry()
    }

    fun act(kind: LibraryKind) {
        if (closed || state.value.freshnessUnverified || storage.value != null || session.value !is WebSessionState.Ready) return
        val detail = (state.value.section as? SectionState.Content)?.value ?: return
        if (detail.ref != validated.ref || !holder.accountState.value.canToggle(kind, library != null)) return
        val actions = library ?: return
        when (kind) {
            LibraryKind.WATCHLIST -> actions.toggleWatchlist(detail.toTitle())
            LibraryKind.FAVOURITE -> actions.toggleFavourite(detail.toTitle())
            LibraryKind.RATING -> actions.openRate(detail.toTitle())
        }
    }

    fun close() {
        if (closed) return
        closed = true
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
