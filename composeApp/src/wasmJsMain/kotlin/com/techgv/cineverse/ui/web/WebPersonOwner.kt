package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageException
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.PersonDetail
import com.techgv.cineverse.presentation.PersonStateHolder
import com.techgv.cineverse.presentation.SectionErrorKind
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class WebPersonEntry(val id: Int, val index: Int, val offset: Int)

/** Present scalars are never coerced, repaired or erased, including before the first settings read. */
internal fun validateWebPersonEntry(entry: WebEntry): WebPersonEntry {
    val route = entry.route as? WebRoute.Person ?: throw IllegalArgumentException("Not a person route")
    require(route.id > 0)
    entry.getString("person.id")?.let { require(it == route.id.toString()) }
    entry.getString("bioExpanded")?.let { require(it == "true" || it == "false") }
    fun scalar(key: String): Int {
        val raw = entry.getString(key) ?: return 0
        val value = raw.toIntOrNull()
        require(value != null && value >= 0 && value.toString() == raw)
        return value
    }
    return WebPersonEntry(route.id, scalar("person.index"), scalar("person.offset"))
}

internal enum class WebPersonReader { Unknown, Ready, Failed }

/** Identity, not value equality: one fresh shared holder consumes exactly this immutable input. */
internal class WebPersonContext internal constructor(
    val settings: Settings,
    val readerEpoch: Long,
    val appSnapshot: Long,
)

internal data class WebPersonReadState(
    val reader: WebPersonReader = WebPersonReader.Unknown,
    val settingsFailure: BrowserStorageFailure? = null,
    val section: SectionState<PersonDetail> = SectionState.Loading,
    val context: WebPersonContext? = null,
    val retained: PersonDetail? = null,
    val retainedContext: WebPersonContext? = null,
    val primaryCompleted: Boolean = false,
    val bioExpanded: Boolean = false,
    val appSnapshot: Long,
) {
    val verified: Boolean get() = reader == WebPersonReader.Ready && context != null && primaryCompleted
    val displayed: PersonDetail? get() = (section as? SectionState.Content)?.value ?: retained
}

/**
 * Entry-owned raw STRICT settings reader; never pass a normalized/permissive store at the root.
 * Root MUST call invalidateContext on generation/session/focus invalidation (the counter is snapshot-only).
 * Each language/recovery/catalog retry owns a fresh PersonStateHolder, never a live-settings bridge.
 * Completion proves this instance's primary result, including cache hits, NOT server freshness.
 */
internal class WebPersonOwner(
    private val entry: WebEntry,
    private val catalog: CatalogRepository,
    private val generation: GenerationCounter,
    private val settings: Flow<Settings>,
    val storage: StateFlow<BrowserStorageFailure?>,
    private val retryUpstreamReads: () -> Unit,
    private val reportStorageFailure: (StorageException) -> Unit,
    private val navigate: (MediaRef) -> Unit,
    private val goBack: () -> Unit,
    parentScope: CoroutineScope,
) {
    val validated = validateWebPersonEntry(entry)
    private val job = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(WebPersonReadState(
        bioExpanded = entry.getString("bioExpanded") == "true", appSnapshot = generation.current))
    val state = mutableState.asStateFlow()
    val imageBase: String get() = catalog.imageBaseUrl()
    private class Read(val context: WebPersonContext, val job: Job) {
        lateinit var holder: PersonStateHolder
    }
    private var read: Read? = null
    private var reader: Job? = null
    private var epoch = 0L
    private var actual: Settings? = null
    private var closed = false
    private fun active() = !closed && job.isActive

    init {
        scope.launch {
            var previous = storage.value
            storage.collect { failure ->
                val recovered = previous != null && failure == null
                previous = failure
                if (recovered && state.value.reader == WebPersonReader.Failed) subscribeSettings()
            }
        }
        subscribeSettings()
    }

    /** Revoke logically BEFORE cancellation: cancellation-resistant old IO is never joined. */
    private fun revoke() {
        val old = read
        read = null
        old?.job?.cancel()
    }

    private fun readerCurrent(ticket: Long) = active() && epoch == ticket
    private fun current(candidate: Read): Boolean = active() && read === candidate && candidate.job.isActive &&
        state.value.reader == WebPersonReader.Ready && epoch == candidate.context.readerEpoch &&
        actual?.language == candidate.context.settings.language && generation.isCurrent(candidate.context.appSnapshot) && validSaver()

    private fun subscribeSettings() {
        if (!active()) return
        val ticket = ++epoch
        actual = null
        val oldReader = reader
        reader = null
        revoke()
        oldReader?.cancel()
        if (!readerCurrent(ticket)) return
        mutableState.value = state.value.copy(reader = WebPersonReader.Unknown, settingsFailure = null,
            section = SectionState.Loading, context = null, primaryCompleted = false, appSnapshot = generation.current)
        if (!readerCurrent(ticket) || !validSaver()) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                settings.collect { captured ->
                    ensureActive()
                    if (!readerCurrent(ticket) || !validSaver()) return@collect
                    if (captured.language !in Settings.LANGUAGES) throw BrowserStorageException(BrowserStorageFailure.Corrupt)
                    val replace = actual?.language != captured.language || read == null ||
                        read?.context?.appSnapshot != generation.current
                    actual = captured
                    if (replace) {
                        mutableState.value = state.value.copy(reader = WebPersonReader.Ready, settingsFailure = null,
                            section = SectionState.Loading, context = null, primaryCompleted = false)
                        if (readerCurrent(ticket)) replaceHolder(captured, ticket)
                    }
                }
            } catch (failure: StorageException) {
                if (!readerCurrent(ticket)) return@launch
                actual = null
                revoke()
                if (!readerCurrent(ticket)) return@launch
                val sanitized = BrowserStorageException((failure as? BrowserStorageException)?.failure ?: BrowserStorageFailure.Unavailable)
                mutableState.value = state.value.copy(reader = WebPersonReader.Failed,
                    settingsFailure = sanitized.failure,
                    section = SectionState.Loading, context = null, primaryCompleted = false)
                if (readerCurrent(ticket)) reportStorageFailure(sanitized)
            }
        }
        reader = next
        next.start()
    }

    private fun replaceHolder(captured: Settings, ticket: Long) {
        if (!readerCurrent(ticket) || !validSaver() || state.value.reader != WebPersonReader.Ready || actual?.language != captured.language) return
        val candidate = Read(WebPersonContext(captured, ticket, generation.current), Job(job))
        val old = read
        read = candidate
        old?.job?.cancel()
        if (!current(candidate)) { candidate.job.cancel(); return }
        mutableState.value = state.value.copy(section = SectionState.Loading, context = candidate.context,
            primaryCompleted = false, appSnapshot = candidate.context.appSnapshot)
        if (!current(candidate)) { candidate.job.cancel(); return }
        val child = CoroutineScope(scope.coroutineContext + candidate.job)
        candidate.holder = PersonStateHolder(validated.id, catalog, flowOf(captured), generation, entry, child)
        if (!current(candidate)) { candidate.job.cancel(); return }
        child.launch {
            candidate.holder.person.collect { section ->
                if (!current(candidate)) return@collect
                val person = (section as? SectionState.Content)?.value
                if (person != null && person.id != validated.id) {
                    mutableState.value = state.value.copy(section = SectionState.Error(SectionErrorKind.Generic), primaryCompleted = false)
                } else {
                    mutableState.value = state.value.copy(section = section,
                        retained = person ?: state.value.retained,
                        retainedContext = if (person != null) candidate.context else state.value.retainedContext,
                        primaryCompleted = person != null,
                        bioExpanded = candidate.holder.bioExpanded.value)
                }
            }
        }
    }

    /** Explicit read recovery reaches both upstream and the actual cold reader, even for equal Settings. */
    fun retryReads() {
        if (!active() || !validSaver()) return
        val ticket = ++epoch
        // Revoke before the external callback, which may synchronously dispose or invalidate this entry.
        actual = null
        val oldReader = reader
        reader = null
        revoke()
        oldReader?.cancel()
        if (!readerCurrent(ticket)) return
        mutableState.value = state.value.copy(reader = WebPersonReader.Unknown, context = null,
            section = SectionState.Loading, primaryCompleted = false)
        if (!readerCurrent(ticket)) return
        retryUpstreamReads()
        if (readerCurrent(ticket)) subscribeSettings()
    }

    fun invalidateContext() { if (active()) subscribeSettings() }

    private fun validSaver(): Boolean = try {
        validateWebPersonEntry(entry).id == validated.id
    } catch (_: IllegalArgumentException) { false }

    private fun sourceCurrent(source: WebPersonReadState): Boolean {
        val candidate = read
        if (!active() || source !== state.value || candidate == null || !current(candidate)) {
            if (active() && !generation.isCurrent(state.value.appSnapshot)) invalidateContext()
            return false
        }
        return source.context === candidate.context && source.verified && validSaver()
    }

    fun retryPerson(source: WebPersonReadState) {
        if (!active() || source !== state.value || source.reader != WebPersonReader.Ready || !validSaver()) return
        val candidate = read ?: return
        if (source.context !== candidate.context) return
        val captured = actual ?: return
        if (!generation.isCurrent(source.appSnapshot)) { invalidateContext(); return }
        replaceHolder(captured, epoch)
    }

    fun toggleBiography(source: WebPersonReadState) {
        if (!sourceCurrent(source)) return
        val candidate = read ?: return
        candidate.holder.toggleBio()
        if (current(candidate)) mutableState.value = state.value.copy(bioExpanded = candidate.holder.bioExpanded.value)
    }

    fun openTitle(source: WebPersonReadState, ref: MediaRef) {
        if (!sourceCurrent(source) || ref.id <= 0) return
        val person = (source.section as? SectionState.Content)?.value ?: return
        if (person.knownFor.none { it.ref == ref } && person.credits.none { it.ref == ref }) return
        if (sourceCurrent(source)) navigate(ref)
    }

    fun back(source: WebPersonReadState) {
        if (active() && source === state.value && generation.isCurrent(source.appSnapshot)) goBack()
        else if (active()) invalidateContext()
    }

    fun saveScroll(source: WebPersonReadState, index: Int, offset: Int) {
        if (index < 0 || offset < 0 || !sourceCurrent(source)) return
        entry.putInt("person.index", index)
        if (sourceCurrent(source)) entry.putInt("person.offset", offset)
    }

    fun close() {
        if (closed) return
        closed = true
        ++epoch
        actual = null
        revoke()
        job.cancel()
    }
}
