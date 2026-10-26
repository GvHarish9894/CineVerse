package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.needsEnglishFallback
import com.techgv.cineverse.data.tmdb.orderedYouTubeVideos
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.data.tmdb.withEnglishFallback
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Catalog only: button states are [DetailStateHolder.accountState], so they never block the title (§7.4). */
data class DetailUiState(
    val detail: SectionState<Detail> = SectionState.Loading,
    val settingsRead: DetailSettingsRead = DetailSettingsRead.Unknown,
    val request: DetailReadContext? = null,
    val completion: DetailReadCompletion? = null,
    val retained: DetailReadContent? = null,
)

enum class DetailSettingsRead { Unknown, Ready, Failed }

/** Captured primary request. Tokens are holder-local and equality-only, not server freshness or account authority. */
data class DetailReadContext(
    val ref: MediaRef,
    val language: String,
    val region: String,
    val appGeneration: Long,
    val requestToken: Long,
)

/** Only an actual primary Content/Error completes a request; default/manual UI Content supplies no proof. */
data class DetailReadCompletion(val context: DetailReadContext, val result: SectionState<Detail>) {
    init { require(result is SectionState.Content || result is SectionState.Error) }
}

/** Last real success, always tagged with its original request, including after a later primary error. */
data class DetailReadContent(val context: DetailReadContext, val detail: Detail)

/**
 * §7.4 / §7.2 library button rule: enabled only with a library ([hasLibrary]), a known state (never "off" while
 * `account_states` is unknown) and no write of [kind] in flight.
 */
fun LibraryState.canToggle(kind: LibraryKind, hasLibrary: Boolean = true): Boolean = hasLibrary && known && kind !in pending

/** §7.4 Play trailer: the first YouTube video in trailer order; null hides the button. */
fun Detail.trailer(): TrailerEvent.Play? = videos.firstOrNull()?.let { trailerOf(title, it.key, it.name) }

/** §7.4 Episodes: the first listed season (Specials last); null (no seasons) hides the button. */
fun Detail.episodesSeason(): Int? = seasons.firstOrNull()?.seasonNumber

/**
 * §7.4 Detail of [ref]: one catalog request in the settings language; when that is not `en-US`, blank tagline /
 * overview are filled from one cached `en-US` detail (D7, labelled by the mapper) and empty videos are refetched in
 * `en-US`, after the localized detail is already shown (their failure changes nothing). Language / region changes
 * and [retry] reload; a result that only missed a newer app generation reloads rather than staying on Loading.
 * App generation is a snapshot, not an idle observable: owners must forward invalidation/entry events via
 * cancel/retry and compare current authority again at click time. Completion can be cached, not server-fresh.
 * [library] supplies the separate account state (null: [accountState] stays Unknown). [scope]: see [HomeStateHolder].
 */
class DetailStateHolder(
    val ref: MediaRef,
    private val catalog: CatalogRepository,
    private val settings: Flow<Settings>,
    private val appGeneration: GenerationCounter,
    library: LibraryActions? = null,
    scope: CoroutineScope? = null,
    private val onStorageFailure: (StorageException) -> Unit = {},
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    val imageBase: String get() = catalog.imageBaseUrl()

    /** This screen's request generation (§1.1): bumped by every (re)load. */
    private val generation = GenerationCounter()
    private val readerEpoch = GenerationCounter()
    private var readerJob: Job? = null
    private var catalogJob: Job? = null
    private var readsEnabled = true
    private var verifiedTuple: Pair<String, String>? = null
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** Watchlist / favourite / rating of [ref] for the current identity, read separately from the catalog. */
    val accountState: StateFlow<LibraryState> = (library?.state(ref) ?: flowOf(LibraryState.Unknown))
        .stateIn(this.scope, SharingStarted.WhileSubscribed(5_000), LibraryState.Unknown)

    init { retryStorage() }

    /** Catalog-only retry: never reuse a remembered tuple while the raw reader is Unknown/Failed/cancelled. */
    fun retry() {
        val tuple = verifiedTuple ?: return
        if (readsEnabled && scope.isActive && state.value.settingsRead == DetailSettingsRead.Ready) {
            startLoad(readerEpoch.current, tuple)
        }
    }

    /** Restart the raw subscription; the first valid value forces a load even if equal to the old tuple. */
    fun retryStorage() {
        if (!scope.isActive) return
        val epoch = readerEpoch.bump()
        invalidateLoad()
        val oldReader = readerJob
        readerJob = null
        verifiedTuple = null
        readsEnabled = true
        oldReader?.cancel()
        _state.update { if (readerCurrent(epoch)) it.copy(settingsRead = DetailSettingsRead.Unknown, request = null) else it }
        // State observers may synchronously cancel or retry during publication.
        if (!readerCurrent(epoch)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val reader = currentCoroutineContext()[Job]!!
            try {
                settings.collect { value ->
                    if (!readerCurrent(epoch) || !reader.isActive) return@collect
                    val tuple = value.language to value.region
                    val changed = verifiedTuple != tuple
                    verifiedTuple = tuple
                    _state.update { if (readerCurrent(epoch) && reader.isActive) it.copy(settingsRead = DetailSettingsRead.Ready) else it }
                    if (!readerCurrent(epoch) || !reader.isActive) return@collect
                    if (changed) startLoad(epoch, tuple)
                }
            } catch (failure: StorageException) {
                if (!readerCurrent(epoch) || !reader.isActive) return@launch
                val failedEpoch = readerEpoch.bump()
                verifiedTuple = null
                invalidateLoad()
                _state.update { if (readerCurrent(failedEpoch) && reader.isActive) it.copy(settingsRead = DetailSettingsRead.Failed) else it }
                if (readerCurrent(failedEpoch) && reader.isActive && state.value.settingsRead == DetailSettingsRead.Failed) {
                    onStorageFailure(failure)
                }
            }
        }
        readerJob = job
        job.start()
    }

    /** Synchronous logical revocation; only explicit [retryStorage] reactivates reads. Account state is independent. */
    fun cancelReads() {
        readerEpoch.bump()
        readsEnabled = false
        verifiedTuple = null
        invalidateLoad()
        val oldReader = readerJob
        readerJob = null
        oldReader?.cancel()
        _state.update { it.copy(settingsRead = DetailSettingsRead.Unknown) }
    }

    private fun readerCurrent(epoch: Long): Boolean = readsEnabled && scope.isActive && readerEpoch.isCurrent(epoch)

    private fun invalidateLoad(): Long {
        val token = generation.bump() // Invalidate before cancellation; never join cancellation-resistant I/O.
        val old = catalogJob
        catalogJob = null
        old?.cancel()
        return token
    }

    private fun owns(context: DetailReadContext, epoch: Long): Boolean =
        readerCurrent(epoch) && generation.isCurrent(context.requestToken) &&
            verifiedTuple == (context.language to context.region) && state.value.settingsRead == DetailSettingsRead.Ready

    private fun startLoad(epoch: Long, tuple: Pair<String, String>) {
        if (!readerCurrent(epoch) || verifiedTuple != tuple || state.value.settingsRead != DetailSettingsRead.Ready) return
        val token = invalidateLoad()
        if (!generation.isCurrent(token) || !readerCurrent(epoch) || verifiedTuple != tuple) return
        val context = DetailReadContext(ref, tuple.first, tuple.second, appGeneration.current, token)
        val job = scope.launch(start = CoroutineStart.LAZY) { load(context, epoch, currentCoroutineContext()[Job]!!) }
        catalogJob = job
        _state.update {
            if (owns(context, epoch)) it.copy(
                request = context,
                detail = if (it.detail is SectionState.Content) it.detail else SectionState.Loading,
            ) else it
        }
        if (owns(context, epoch) && state.value.request == context) job.start() else job.cancel()
    }

    private fun current(context: DetailReadContext, epoch: Long, job: Job): Boolean {
        if (!job.isActive || !owns(context, epoch) || state.value.request != context) return false
        if (!appGeneration.isCurrent(context.appGeneration)) {
            // Only still-owned work may schedule a replacement; a failed/cancelled reader cannot resurrect.
            startLoad(epoch, context.language to context.region)
            return false
        }
        return true
    }

    private fun publish(context: DetailReadContext, epoch: Long, job: Job, result: SectionState<Detail>) {
        if (!current(context, epoch, job)) return
        _state.update {
            // Pure CAS lambda: rechecks cannot start I/O or issue retries.
            if (job.isActive && owns(context, epoch) && it.request == context && appGeneration.isCurrent(context.appGeneration)) {
                it.copy(
                    detail = result,
                    completion = DetailReadCompletion(context, result),
                    retained = if (result is SectionState.Content) DetailReadContent(context, result.value) else it.retained,
                )
            } else it
        }
    }

    private suspend fun load(context: DetailReadContext, epoch: Long, job: Job) {
        if (!current(context, epoch, job)) return
        var detail = try {
            when (ref.mediaType) {
                MediaType.Movie -> catalog.movieDetail(ref.id, context.language).toDetail(context.region)
                MediaType.Tv -> catalog.tvDetail(ref.id, context.language).toDetail(context.region)
            }
        } catch (e: TmdbException) {
            publish(context, epoch, job, SectionState.Error(e.toSectionErrorKind()))
            return
        }
        if (!current(context, epoch, job)) return
        publish(context, epoch, job, SectionState.Content(detail))
        if (!current(context, epoch, job) || context.language == ENGLISH) return

        // Localized content is visible before optional English text, then videos, under the same context.
        if (detail.needsEnglishFallback) {
            if (!current(context, epoch, job)) return
            val english = optional {
                when (ref.mediaType) {
                    MediaType.Movie -> catalog.movieDetail(ref.id, ENGLISH).let { it.tagline to it.overview }
                    MediaType.Tv -> catalog.tvDetail(ref.id, ENGLISH).let { it.tagline to it.overview }
                }
            }
            if (!current(context, epoch, job)) return
            if (english != null) detail = detail.withEnglishFallback(english.first, english.second)
        }
        if (detail.videos.isEmpty()) {
            if (!current(context, epoch, job)) return
            val videos = optional { catalog.videos(ref.mediaType, ref.id, ENGLISH) }
            if (!current(context, epoch, job)) return
            if (videos != null) detail = detail.copy(videos = orderedYouTubeVideos(videos.results))
        }
        publish(context, epoch, job, SectionState.Content(detail))
    }
}

/** Runs an optional fallback fetch: a TMDB failure yields null. */
internal suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (_: TmdbException) {
    null
}
