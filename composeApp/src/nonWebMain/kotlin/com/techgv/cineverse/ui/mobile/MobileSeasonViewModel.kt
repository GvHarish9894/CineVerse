package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class MobileSeasonScroll(val index: Int = 0, val offset: Int = 0)

/** Referential authority belongs to one actual raw reader and one immutable child holder. */
internal class MobileSeasonContext internal constructor(
    val settings: Settings,
    internal val readerEpoch: Long,
    internal val appGeneration: Long,
    internal val graphContext: Long,
    internal val job: Job,
) {
    internal var holder: SeasonStateHolder? = null
}

internal data class MobileSeasonState(
    val revision: Long = 0L,
    val invalid: Boolean = false,
    val settingsFailed: Boolean = false,
    val stale: Boolean = false,
    val context: MobileSeasonContext? = null,
    val current: SeasonScreenState? = null,
    val retainedShow: Detail? = null,
    val retainedSeason: Season? = null,
) {
    val bodyReady: Boolean get() = current?.show is SectionState.Content &&
        (current.episodes is SectionState.Content || current.episodes == SectionState.Empty)
}

private data class MobileSeasonSaved(val values: Map<String, Int>, val scroll: MobileSeasonScroll)

/** Validate actual scalar types before any subscription/holder; never repair contradictory saved routes. */
private fun validateMobileSeason(seriesId: Int, seasonNumber: Int, saved: SavedStateHandle): MobileSeasonSaved? {
    if (seriesId <= 0 || seasonNumber < 0) return null
    val values = mutableMapOf<String, Int>()
    for (key in listOf("selectedSeason", "openEpisode", "mobileSeason.index", "mobileSeason.offset")) {
        val raw = saved.get<Any?>(key) ?: continue
        if (raw !is Int || raw < 0) return null
        values[key] = raw
    }
    if (values["selectedSeason"]?.let { it != seasonNumber } == true) return null
    return MobileSeasonSaved(values.toMap(), MobileSeasonScroll(values["mobileSeason.index"] ?: 0, values["mobileSeason.offset"] ?: 0))
}

/**
 * One initially inactive native Season entry. Pass graph.settingsFlow itself, not cached/default settings.
 * Graph contextChanges are observed here (content/adult/identity); the root owns setActive/close and may
 * forward contextChanged for additional real generation invalidations. No cancellation joins or retargeting.
 */
internal class MobileSeasonViewModel(
    private val graph: MobileApplication,
    val seriesId: Int,
    val seasonNumber: Int,
    private val savedState: SavedStateHandle,
    private val rawSettings: Flow<Settings>,
    parentScope: CoroutineScope? = null,
    seed: Detail? = null,
) : ViewModel(), MobileCatalogEntryModel {
    private val initial = validateMobileSeason(seriesId, seasonNumber, savedState)
    val scrollTarget: MobileSeasonScroll?
        get() = if (initial == null) null else validateMobileSeason(seriesId, seasonNumber, savedState)?.scroll
    val imageBase: String get() = graph.catalog.imageBaseUrl()
    private val parent = parentScope ?: viewModelScope
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private var closed = false
    private var visible = false
    private var readerEpoch = 0L
    private var readerJob: Job? = null
    private var capturedSettings: Settings? = null
    private var context: MobileSeasonContext? = null
    private var observedGraph = graph.contextChanges.value to graph.settingsFailed.value
    // A seed is only retained (stale, actions disabled) display until this entry's own show read completes.
    private val mutableState = MutableStateFlow(MobileSeasonState(invalid = initial == null,
        retainedShow = seed?.takeIf { initial != null && it.ref == MediaRef(MediaType.Tv, seriesId) &&
            it.seasons.any { season -> season.seasonNumber == seasonNumber } }))
    val state = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        if (initial != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(graph.contextChanges, graph.settingsFailed) { revision, failed -> revision to failed }.collect { next ->
                if (next != observedGraph) {
                    observedGraph = next
                    if (active()) restartReader()
                }
            }
        }
    }

    private fun alive() = !closed && job.isActive && parent.coroutineContext[Job]?.isActive != false
    private fun active() = alive() && visible && !state.value.invalid
    private fun readerCurrent(epoch: Long) = active() && epoch == readerEpoch && !graph.settingsFailed.value
    private fun ownsReader(epoch: Long, reader: Job) = readerCurrent(epoch) && readerJob === reader && reader.isActive
    private fun current(token: MobileSeasonContext) = readerCurrent(token.readerEpoch) && context === token &&
        token.job.isActive && readerJob?.isActive == true && capturedSettings == token.settings &&
        graph.generation.isCurrent(token.appGeneration) && graph.contextChanges.value == token.graphContext

    /** Detach all logical authority before publishing or cancelling: either can synchronously reenter. */
    private fun resetReader(failed: Boolean = false): Long {
        val epoch = ++readerEpoch
        val oldReader = readerJob
        val oldContext = context
        readerJob = null
        context = null
        capturedSettings = null
        mutableState.value = state.value.copy(revision = state.value.revision + 1, context = null, current = null, settingsFailed = failed,
            stale = state.value.retainedShow != null || state.value.retainedSeason != null)
        oldContext?.job?.cancel()
        oldReader?.cancel()
        return epoch
    }

    private fun restartReader() {
        val epoch = resetReader(graph.settingsFailed.value)
        if (readerCurrent(epoch)) beginReader(epoch)
    }

    private fun beginReader(epoch: Long) {
        if (!readerCurrent(epoch)) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val reader = currentCoroutineContext()[Job]!!
            if (!ownsReader(epoch, reader)) return@launch
            try {
                rawSettings.collect { actual ->
                    if (!ownsReader(epoch, reader)) return@collect
                    if (actual.language !in Settings.LANGUAGES || actual.region !in Settings.REGIONS) {
                        resetReader(failed = true)
                        return@collect
                    }
                    replaceHolder(epoch, reader, actual)
                }
                // An ended raw reader is unknown, not a permanently actionable cached preference value.
                if (ownsReader(epoch, reader)) resetReader()
            } catch (_: StorageException) {
                if (ownsReader(epoch, reader)) resetReader(failed = true)
            }
        }
        if (!readerCurrent(epoch)) { next.cancel(); return }
        readerJob = next
        next.start()
    }

    private fun replaceHolder(epoch: Long, reader: Job, actual: Settings) {
        if (!ownsReader(epoch, reader)) return
        val old = context
        context = null
        capturedSettings = null
        mutableState.value = state.value.copy(revision = state.value.revision + 1, context = null, current = null,
            stale = state.value.retainedShow != null || state.value.retainedSeason != null)
        old?.job?.cancel()
        if (!ownsReader(epoch, reader)) return
        val snapshot = validateMobileSeason(seriesId, seasonNumber, savedState)
        if (snapshot == null) {
            val revision = resetReader()
            if (revision == readerEpoch) mutableState.value = state.value.copy(invalid = true)
            return
        }
        capturedSettings = actual
        val token = MobileSeasonContext(actual, epoch, graph.generation.current, graph.contextChanges.value, SupervisorJob(job))
        context = token
        mutableState.value = state.value.copy(context = token, settingsFailed = false)
        if (!current(token)) { token.job.cancel(); return }
        val child = CoroutineScope(scope.coroutineContext + token.job)
        val holder = SeasonStateHolder(seriesId, seasonNumber, graph.catalog,
            flowOf(actual).filter { current(token) }, graph.generation, ContextSaver(snapshot.values, token), child,
            fallbackToFirstSeason = false)
        token.holder = holder
        if (!current(token)) { token.job.cancel(); return }
        child.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.state.collect { result -> if (current(token)) publish(token, result) }
        }
    }

    private fun publish(token: MobileSeasonContext, result: SeasonScreenState) {
        if (!current(token)) return
        val show = result.show.let { section ->
            if (section is SectionState.Content && (section.value.ref != MediaRef(MediaType.Tv, seriesId) ||
                    section.value.seasons.any { it.seasonNumber < 0 })) SectionState.Error(SectionErrorKind.NotAvailable) else section
        }
        val episodes = result.episodes.let { section ->
            if (show is SectionState.Error && result.show is SectionState.Content) SectionState.Error(SectionErrorKind.NotAvailable)
            else if (section is SectionState.Content && (show !is SectionState.Content || result.selected != seasonNumber ||
                    section.value.seriesId != seriesId || section.value.seasonNumber != seasonNumber ||
                    section.value.episodes.any { it.seasonNumber != seasonNumber || it.episodeNumber < 0 } ||
                    section.value.episodes.map { it.episodeNumber }.distinct().size != section.value.episodes.size))
                SectionState.Error(SectionErrorKind.NotAvailable) else section
        }
        val panel = result.openEpisode?.takeIf { number -> episodes is SectionState.Content && episodes.value.episodes.any { it.episodeNumber == number } }
        val safe = result.copy(show = show, episodes = episodes, openEpisode = panel)
        mutableState.value = state.value.copy(current = safe,
            retainedShow = (show as? SectionState.Content)?.value ?: state.value.retainedShow,
            retainedSeason = (episodes as? SectionState.Content)?.value ?: state.value.retainedSeason,
            stale = (show !is SectionState.Content && state.value.retainedShow != null) ||
                (episodes !is SectionState.Content && episodes != SectionState.Empty && state.value.retainedSeason != null))
    }

    /** Also reject an event that happened before the owner's graph collector could run. */
    fun visibleState(snapshot: MobileSeasonState): MobileSeasonState =
        if (snapshot.context != null && !current(snapshot.context)) snapshot.copy(context = null, current = null,
            settingsFailed = snapshot.settingsFailed || graph.settingsFailed.value,
            stale = snapshot.retainedShow != null || snapshot.retainedSeason != null) else snapshot

    override fun setActive(active: Boolean) {
        if (!alive() || visible == active) return
        visible = active
        if (active && initial != null && !state.value.invalid) restartReader() else resetReader()
    }

    fun contextChanged() { if (active()) restartReader() }

    fun retryReads(renderedRevision: Long = state.value.revision) {
        if (!active() || renderedRevision != state.value.revision) return
        val epoch = resetReader()
        if (!active() || epoch != readerEpoch) return
        graph.retrySettings()
        // A synchronous graph event may already have installed a newer actual reader.
        if (!active() || epoch != readerEpoch) return
        if (graph.settingsFailed.value) resetReader(failed = true) else beginReader(epoch)
    }

    fun retryShow(rendered: MobileSeasonContext) { if (current(rendered)) restartReader() }
    fun retryEpisodes(rendered: MobileSeasonContext) {
        if (current(rendered) && state.value.current?.selected == seasonNumber) restartReader()
    }

    fun selectSeason(rendered: MobileSeasonContext, number: Int, navigator: MobileNavigator) {
        if (!current(rendered)) return
        val show = (state.value.current?.show as? SectionState.Content)?.value ?: return
        if (show.seasons.any { it.seasonNumber == number }) {
            graph.seedSeason(show)
            navigator.open(MobileDestination.Season(show.ref.id, number))
        }
    }

    fun show(rendered: MobileSeasonContext, navigator: MobileNavigator) {
        if (!current(rendered)) return
        val show = (state.value.current?.show as? SectionState.Content)?.value ?: return
        navigator.open(MobileDestination.Detail(show.ref))
    }

    fun openEpisode(rendered: MobileSeasonContext, number: Int) {
        if (!current(rendered)) return
        val season = (state.value.current?.episodes as? SectionState.Content)?.value ?: return
        if (season.episodes.any { it.episodeNumber == number }) rendered.holder?.openEpisode(number)
    }

    fun closeEpisode(rendered: MobileSeasonContext) {
        if (current(rendered) && state.value.current?.episodes is SectionState.Content) rendered.holder?.closeEpisode()
    }

    fun saveScroll(rendered: MobileSeasonContext, index: Int, offset: Int) {
        if (!current(rendered) || !state.value.bodyReady || index < 0 || offset < 0) return
        savedState["mobileSeason.index"] = index
        if (current(rendered) && state.value.bodyReady) savedState["mobileSeason.offset"] = offset
    }

    fun back(renderedRevision: Long = state.value.revision, onBack: () -> Unit) {
        if (alive() && visible && renderedRevision == state.value.revision) onBack()
    }

    fun close() {
        if (closed) return
        closed = true
        visible = false
        resetReader()
        job.cancel()
    }

    override fun onCleared() = close()

    private inner class ContextSaver(private val snapshot: Map<String, Int>, private val token: MobileSeasonContext) : StateSaver {
        override fun getInt(key: String): Int? = snapshot[key]
        override fun getString(key: String): String? = null
        override fun getBoolean(key: String): Boolean? = null
        override fun putInt(key: String, value: Int?) {
            if (!current(token)) return
            if (key == "selectedSeason" && value == seasonNumber) savedState[key] = value
            if (key == "openEpisode" && (value == null || value >= 0)) savedState[key] = value
        }
        override fun putString(key: String, value: String?) = Unit
        override fun putBoolean(key: String, value: Boolean?) = Unit
    }
}
