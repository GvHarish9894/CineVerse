package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal sealed interface WebSeasonReader {
    data object Unknown : WebSeasonReader
    data class Ready(val settings: Settings, val epoch: Long) : WebSeasonReader
    data class Failed(val failure: BrowserStorageFailure) : WebSeasonReader
}

/** Identity is per actual holder, not value equality or a claim of HTTP freshness. */
internal class WebSeasonContext(val settings: Settings, val appSnapshot: Long, internal val readerEpoch: Long) {
    internal lateinit var job: Job
    internal var holder: SeasonStateHolder? = null
}

internal data class WebSeasonShow(val context: WebSeasonContext, val value: Detail)
internal data class WebSeasonEpisodes(val context: WebSeasonContext, val value: Season)
internal data class WebSeasonState(
    val invalidSavedState: Boolean = false,
    val reader: WebSeasonReader = WebSeasonReader.Unknown,
    val context: WebSeasonContext? = null,
    val current: SeasonScreenState? = null,
    val retainedShow: WebSeasonShow? = null,
    val retainedSeason: WebSeasonEpisodes? = null,
) {
    val bodyReady: Boolean get() = current?.show is SectionState.Content &&
        (current.episodes is SectionState.Content || current.episodes == SectionState.Empty)
}

private data class WebSeasonSaved(val values: Map<String, String>, val index: Int, val offset: Int)

private fun validateWebSeason(entry: WebEntry): WebSeasonSaved? {
    val route = entry.route as? WebRoute.Season ?: return null
    if (route.id <= 0 || route.number < 0) return null
    val keys = listOf("selectedSeason", "openEpisode", "season.seriesId", "season.number", "season.index", "season.offset")
    val raw = keys.mapNotNull { key -> entry.getString(key)?.let { key to it } }.toMap()
    fun number(key: String): Int? = raw[key]?.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toIntOrNull()
    if (raw.keys.any { number(it) == null }) return null
    if ("selectedSeason" in raw && number("selectedSeason") != route.number) return null
    if (("season.seriesId" in raw) != ("season.number" in raw)) return null
    if ("season.seriesId" in raw && (number("season.seriesId") != route.id || number("season.number") != route.number)) return null
    return WebSeasonSaved(raw, number("season.index") ?: 0, number("season.offset") ?: 0)
}

/**
 * Disconnected entry owner. Root supplies the actual strict cold settings reader, actual storage status and
 * invalidation events (including idle generation changes); neither cached/default settings nor polling is authority.
 * WebEntry's Map<String,String> cannot certify pre-entry JSON types: root/parser must reject those before this seam.
 */
internal class WebSeasonOwner(
    private val entry: WebEntry,
    private val catalog: CatalogRepository,
    private val settings: Flow<Settings>,
    private val storageFailure: StateFlow<BrowserStorageFailure?>,
    private val generation: GenerationCounter,
    contextInvalidations: Flow<Unit>,
    parentScope: CoroutineScope,
    private val retryUpstream: () -> Unit,
    private val reportStorageFailure: (StorageException) -> Unit,
    private val onBack: () -> Unit,
    private val onShow: (Int) -> Unit,
    private val onSeason: (Int, Int) -> Unit,
) {
    // Validate before creating any scope, subscription or holder. Invalid raw history is never repaired.
    private val saved = validateWebSeason(entry)
    val scrollTarget: Pair<Int, Int> = (saved?.index ?: 0) to (saved?.offset ?: 0)
    val route: WebRoute.Season? = entry.route as? WebRoute.Season
    private val parentJob = parentScope.coroutineContext[Job]
    private val job = if (saved != null && parentJob?.isActive != false) SupervisorJob(parentJob) else null
    private val scope = job?.let { CoroutineScope(parentScope.coroutineContext + it) }
    private var closed = false
    private var readerEpoch = 0L
    private var readerJob: Job? = null
    private var rawSettings: Settings? = null
    private var context: WebSeasonContext? = null
    private val mutableState = MutableStateFlow(WebSeasonState(invalidSavedState = saved == null))
    val state: StateFlow<WebSeasonState> = mutableState.asStateFlow()

    init {
        scope?.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope?.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!active()) return@launch
            contextInvalidations.collect {
                val ready = rawSettings
                if (active() && ready != null && storageFailure.value == null) replaceHolder(ready, readerEpoch)
            }
        }
        scope?.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!active()) return@launch
            storageFailure.collect { failure ->
                if (!active()) return@collect
                if (failure != null) resetReader(WebSeasonReader.Failed(failure))
                else if (readerJob == null) beginReader(resetReader(WebSeasonReader.Unknown))
            }
        }
    }

    private fun alive() = !closed && parentJob?.isActive != false
    private fun active() = alive() && job?.isActive == true && !state.value.invalidSavedState
    private fun readerCurrent(epoch: Long) = active() && epoch == readerEpoch && storageFailure.value == null
    private fun current(token: WebSeasonContext): Boolean = readerCurrent(token.readerEpoch) &&
        context === token && token.job.isActive && generation.isCurrent(token.appSnapshot) &&
        rawSettings == token.settings && state.value.reader == WebSeasonReader.Ready(token.settings, token.readerEpoch)

    private fun revokeHolder() {
        val old = context
        context = null // Revoke before cancellation; do not join cancellation-resistant catalog IO.
        mutableState.value = state.value.copy(context = null, current = null)
        old?.job?.cancel()
    }

    private fun resetReader(reader: WebSeasonReader): Long {
        val epoch = ++readerEpoch
        rawSettings = null
        val old = readerJob
        readerJob = null
        revokeHolder()
        old?.cancel()
        if (epoch == readerEpoch) mutableState.value = state.value.copy(reader = reader, context = null, current = null)
        return epoch
    }

    private fun beginReader(epoch: Long) {
        if (!readerCurrent(epoch)) return
        val ownerScope = scope ?: return
        val read = ownerScope.launch(start = CoroutineStart.LAZY) {
            if (!readerCurrent(epoch)) return@launch
            try {
                settings.distinctUntilChanged().collect { captured ->
                    if (!readerCurrent(epoch)) return@collect
                    if (captured.language !in Settings.LANGUAGES || captured.region !in Settings.REGIONS) {
                        failReader(epoch, BrowserStorageFailure.Corrupt)
                        return@collect
                    }
                    rawSettings = null
                    revokeHolder()
                    if (!readerCurrent(epoch)) return@collect
                    rawSettings = captured
                    mutableState.value = state.value.copy(reader = WebSeasonReader.Ready(captured, epoch), context = null, current = null)
                    if (readerCurrent(epoch) && rawSettings == captured) replaceHolder(captured, epoch)
                }
            } catch (failure: StorageException) {
                failReader(epoch, (failure as? BrowserStorageException)?.failure ?: BrowserStorageFailure.Unavailable)
            }
        }
        readerJob = read
        read.start()
    }

    private fun failReader(epoch: Long, failure: BrowserStorageFailure) {
        if (!readerCurrent(epoch)) return
        val failedEpoch = resetReader(WebSeasonReader.Failed(failure))
        if (active() && failedEpoch == readerEpoch) reportStorageFailure(BrowserStorageException(failure))
    }

    fun retryStorage() {
        if (!active()) return
        val epoch = resetReader(WebSeasonReader.Unknown)
        if (!active() || epoch != readerEpoch) return
        retryUpstream()
        if (!active() || epoch != readerEpoch) return
        val failure = storageFailure.value
        if (failure == null) beginReader(epoch)
        else mutableState.value = state.value.copy(reader = WebSeasonReader.Failed(failure))
    }

    private fun replaceHolder(captured: Settings, epoch: Long) {
        if (!readerCurrent(epoch) || rawSettings != captured) return
        val snapshot = validateWebSeason(entry)
        if (snapshot == null) {
            resetReader(WebSeasonReader.Unknown)
            mutableState.value = state.value.copy(invalidSavedState = true)
            return
        }
        revokeHolder()
        if (!readerCurrent(epoch) || rawSettings != captured || context != null) return
        val token = WebSeasonContext(captured, generation.current, epoch)
        token.job = SupervisorJob(job)
        context = token
        mutableState.value = state.value.copy(context = token, current = null)
        if (!current(token)) { token.job.cancel(); return }
        val child = CoroutineScope(scope!!.coroutineContext + token.job)
        val saver = ContextSaver(snapshot.values) { current(token) }
        // A synchronous constructor load can write history and reenter/dispose the owner.
        val holder = SeasonStateHolder(route!!.id, route.number, catalog, flowOf(captured).filter { current(token) }, generation, saver, child,
            fallbackToFirstSeason = false)
        token.holder = holder
        if (!current(token)) { token.job.cancel(); return }
        child.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.state.collect { result -> if (current(token)) publish(token, result) }
        }
    }

    private fun publish(token: WebSeasonContext, result: SeasonScreenState) {
        if (!current(token)) return
        val show = result.show.let {
            if (it is SectionState.Content && (it.value.ref != MediaRef(MediaType.Tv, route!!.id) ||
                    it.value.seasons.any { season -> season.seasonNumber < 0 }))
                SectionState.Error(SectionErrorKind.NotAvailable) else it
        }
        val episodes = result.episodes.let {
            if (it is SectionState.Content && (show !is SectionState.Content || it.value.seriesId != route!!.id ||
                    it.value.seasonNumber != route.number || it.value.episodes.any { episode ->
                        episode.seasonNumber != route.number || episode.episodeNumber < 0
                    } || it.value.episodes.map { episode -> episode.episodeNumber }.distinct().size != it.value.episodes.size))
                SectionState.Error(SectionErrorKind.NotAvailable) else it
        }
        val panel = result.openEpisode?.takeIf { number ->
            episodes is SectionState.Content && episodes.value.episodes.any { it.episodeNumber == number }
        }
        val safe = result.copy(show = show, episodes = episodes, openEpisode = panel)
        mutableState.value = state.value.copy(current = safe,
            retainedShow = if (show is SectionState.Content) WebSeasonShow(token, show.value) else state.value.retainedShow,
            retainedSeason = if (episodes is SectionState.Content) WebSeasonEpisodes(token, episodes.value) else state.value.retainedSeason)
    }

    fun retry(rendered: WebSeasonContext) {
        if (current(rendered)) replaceHolder(rendered.settings, rendered.readerEpoch)
    }

    fun selectSeason(rendered: WebSeasonContext, number: Int) {
        if (!current(rendered)) return
        val show = (state.value.current?.show as? SectionState.Content)?.value ?: return
        if (show.seasons.any { it.seasonNumber == number }) onSeason(route!!.id, number)
    }

    fun openEpisode(rendered: WebSeasonContext, number: Int) {
        if (!current(rendered)) return
        val episodes = (state.value.current?.episodes as? SectionState.Content)?.value ?: return
        if (episodes.episodes.any { it.episodeNumber == number }) rendered.holder?.openEpisode(number)
    }

    fun closeEpisode(rendered: WebSeasonContext) {
        if (current(rendered) && state.value.bodyReady) rendered.holder?.closeEpisode()
    }

    fun back() { if (alive()) onBack() }
    fun show(rendered: WebSeasonContext) {
        if (current(rendered) && state.value.current?.show is SectionState.Content) onShow(route!!.id)
    }

    fun saveScroll(rendered: WebSeasonContext, index: Int, offset: Int) {
        if (index < 0 || offset < 0 || !current(rendered) || !state.value.bodyReady) return
        entry.putInt("season.index", index)
        if (current(rendered) && state.value.bodyReady) entry.putInt("season.offset", offset)
    }

    fun close() {
        if (closed) return
        closed = true
        resetReader(WebSeasonReader.Unknown)
        job?.cancel()
    }

    private inner class ContextSaver(private val snapshot: Map<String, String>, private val writable: () -> Boolean) : StateSaver {
        override fun getString(key: String) = snapshot[key]
        override fun getInt(key: String) = snapshot[key]?.toIntOrNull()
        override fun getBoolean(key: String) = snapshot[key]?.toBooleanStrictOrNull()
        override fun putString(key: String, value: String?) { if (writable()) entry.putString(key, value) }
        override fun putInt(key: String, value: Int?) { if (writable()) entry.putInt(key, value) }
        override fun putBoolean(key: String, value: Boolean?) { if (writable()) entry.putBoolean(key, value) }
    }
}
