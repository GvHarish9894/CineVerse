package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.displayStyle
import kotlinx.coroutines.flow.distinctUntilChanged

/** Root handoff: WebLibrary(entry, runtime, resultIdentity, resultPage, navigate).
 * Uses runtime.session (not nullable auth hints) and runtime.libraryHolder(entry, childScope).
 * REQUIRED resultIdentity must extract the reviewed producer identity from the SAME immutable state.
 * resultPage must likewise extract each loaded ACCOUNT page (1..500); null is unknown/unpaged.
 * No production extractor exists yet: root integration is BLOCKED on that shared seam. Do not infer it
 * from signedIn/accountName/current session. Card mutations intentionally await LibraryActions binding.
 */
@Composable
fun WebLibrary(
    entry: WebEntry,
    runtime: WebRuntime,
    resultIdentity: (LibraryUiState) -> WebLibraryResultIdentity?,
    resultPage: (LibraryUiState, MediaType) -> Int?,
    navigate: (WebRoute) -> Unit,
) {
    val session by runtime.session.collectAsState()
    val storage by runtime.storageFailure.collectAsState()
    val scope = rememberCoroutineScope()
    val valid = remember(entry) { try { validateWebLibraryEntry(entry); true } catch (_: IllegalArgumentException) { false } }
    if (!valid) {
        Text("This Library entry has invalid saved state. It was not changed.", Modifier.testTag("library-invalid"))
        return
    }
    val ready = session as? WebSessionState.Ready
    if (ready == null) {
        Column {
            Text(if (session is WebSessionState.Failed) "Library identity could not be verified. Saved data is unchanged." else "Verifying Library identity…")
            PillButton("Verify session", runtime::revalidateSession)
        }
        return
    }
    val owner = remember(entry, ready, runtime, resultIdentity, resultPage) {
        WebLibraryOwner(entry, scope, ready, { runtime.session.value }, resultIdentity, resultPage, runtime::retryStorage, runtime::libraryHolder)
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val state by owner.state.collectAsState()
    val pages by owner.pages.collectAsState()
    // Check again at presentation time; a stale collector emission must not reveal an old identity.
    WebLibraryContent(entry, state?.takeIf { owner.visibleState() === it }, pages, storage, navigate,
        owner::loadMore, owner::retry, owner::revalidate, owner::retryStorage, runtime::revalidateSession)
}

@Composable
internal fun WebLibraryContent(
    entry: WebEntry,
    state: LibraryUiState?,
    pages: Map<MediaType, Int?>,
    storage: BrowserStorageFailure?,
    navigate: (WebRoute) -> Unit,
    loadMore: (MediaType) -> Unit,
    retry: (MediaType) -> Unit,
    revalidate: () -> Unit,
    retryStorage: () -> Unit,
    verifySession: () -> Unit,
) {
    val restored = remember(entry) { validateWebLibraryEntry(entry) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth > 860.dp
        val phone = maxWidth <= 640.dp
        Column(Modifier.widthIn(max = 1320.dp).fillMaxSize().padding(horizontal = if (phone) 16.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("My Library", style = displayStyle(if (phone) 36.sp else 48.sp), modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryTab.entries.forEach { tab ->
                    val count = if (state?.tab == tab) libraryLoadedCount(state) else null
                    Chip("${if (tab == LibraryTab.Rated) "Ratings" else tab.label} · ${count?.let { "$it loaded" } ?: "count unknown"}", tab == restored.tab, { navigate(tab.libraryRoute()) })
                }
            }
            if (storage != null) WebStorageNotice(storage, retryStorage, Modifier.testTag("library-storage-recovery"))
            if (state == null) {
                Text("Library source is not yet verified. No private entries are shown.")
                PillButton("Verify session", verifySession)
                if (storage == null) PillButton("Retry saved reads", retryStorage)
            } else {
                Text(if (state.signedIn) "${state.accountName.orEmpty()} · TMDB account lists" else "Guest library · saved on this browser; kept separate from account lists")
                PillButton("Refresh Library", revalidate, variant = ButtonVariant.Ghost)
                if (MediaType.entries.any { media ->
                    val group = state.group(media)
                    (group.section as? SectionState.Error)?.kind == SectionErrorKind.SessionExpired || group.moreError == SectionErrorKind.SessionExpired || group.refreshError == SectionErrorKind.SessionExpired
                }) PillButton("Verify session", verifySession)
                val groups: @Composable (Modifier) -> Unit = { modifier ->
                    MediaType.entries.forEach { media ->
                        key(entry, media) {
                            WebLibraryGrid(entry, media, state.group(media), state.tab, state.signedIn, pages.getValue(media), restored.positions.getValue(media),
                                { loadMore(media) }, { retry(media) }, navigate, modifier)
                        }
                    }
                }
                if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) { groups(Modifier.weight(1f).fillMaxHeight()) }
                else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { groups(Modifier.weight(1f).fillMaxWidth()) }
            }
        }
    }
}

internal fun libraryLoadedCount(state: LibraryUiState): Int? {
    fun LibraryGroup.count(): Int? = when (val value = section) {
        SectionState.Empty -> 0
        is SectionState.Content -> value.value.size
        else -> null
    }
    val movies = state.movies.count() ?: return null
    val tv = state.tv.count() ?: return null
    return movies + tv
}

internal fun MediaRef.libraryDetailRoute(): WebRoute.Detail {
    require(id > 0) { "Invalid Library title ID" }
    return WebRoute.Detail(if (mediaType == MediaType.Movie) "movie" else "tv", id)
}

@Composable
private fun WebLibraryGrid(
    entry: WebEntry, media: MediaType, group: LibraryGroup, tab: LibraryTab, paged: Boolean, page: Int?,
    target: WebLibraryPosition, loadMore: () -> Unit, retry: () -> Unit, navigate: (WebRoute) -> Unit, modifier: Modifier,
) {
    val label = if (media == MediaType.Movie) "Movies" else "TV Shows"
    val items = (group.section as? SectionState.Content)?.value
    val scroll = remember(entry, media) { LazyGridState() }
    var restored by remember(entry, media) { mutableStateOf(false) }
    // Only restore bounded saved pages. An error stops here until an explicit retry; no scroll retry loop.
    LaunchedEffect(group, page, restored) {
        if ((paged && page == null) || restored || group.loadingMore || group.moreError != null || group.refreshError != null) return@LaunchedEffect
        if (group.section !is SectionState.Content && group.section != SectionState.Empty) return@LaunchedEffect
        if (page != null && page < target.page && group.hasMore && page < 500) loadMore()
        else {
            scroll.scrollToItem(target.index.coerceAtMost((items?.size ?: 1) + 2), target.offset)
            restored = true
        }
    }
    LaunchedEffect(scroll, restored) {
        if (restored) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            entry.putInt(media.libraryKey("index"), index)
            entry.putInt(media.libraryKey("offset"), offset)
        }
    }
    LaunchedEffect(page, restored) { if (restored && page != null) entry.putInt(media.libraryKey("page"), page) }
    LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), state = scroll,
        modifier = modifier.testTag("library-${media.name}-grid"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(label, style = displayStyle(26.sp), modifier = Modifier.semantics { heading() })
                Text(when (group.section) {
                    is SectionState.Content -> "${items!!.size} loaded · not a total count"
                    SectionState.Empty -> "0 loaded"
                    else -> "Loaded count unknown"
                })
            }
        }
        when (val section = group.section) {
            SectionState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Text("Loading $label…") }
            SectionState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState("No $label in ${if (tab == LibraryTab.Rated) "Ratings" else tab.label}", "This source returned an empty list.") {
                    PillButton("Browse popular movies", { navigate(WebRoute.Browse("movie")) })
                }
            }
            is SectionState.Error -> item(span = { GridItemSpan(maxLineSpan) }) { WebLibraryError(section.kind, retry, false) }
            is SectionState.Content -> {
                items(section.value, key = { "${it.title.ref.mediaType}:${it.title.ref.id}" }) { item ->
                    BoxWithConstraints {
                        val cardWidth = maxWidth
                        Column {
                            PosterCard(item.title.title, item.title.releaseDate, ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary),
                                { navigate(item.title.ref.libraryDetailRoute()) }, Modifier.testTag("library-${item.title.ref.mediaType}-${item.title.ref.id}"),
                                imageUrl = webImage(item.title.posterPath, "w342"), voteAverage = item.title.voteAverage, voteCount = item.title.voteCount, width = cardWidth)
                            if (tab == LibraryTab.Rated) item.rating?.let { GoldBadge("Your rating · ${oneDecimal(it)} / 10") }
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.testTag("library-${media.name}-paging")) {
                group.refreshError?.let { WebLibraryError(it, retry, true) }
                group.moreError?.let { WebLibraryError(it, retry, true) }
                if (group.loadingMore) Text("Loading more $label…")
                else if (group.hasMore && page != null && page < 500 && group.moreError == null && group.refreshError == null) PillButton("Load more $label", loadMore)
                if (page == 500 && group.hasMore) Text("Reached the 500-page limit.")
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Footer() }
    }
}

@Composable
private fun WebLibraryError(kind: SectionErrorKind, retry: () -> Unit, retained: Boolean) {
    val message = when (kind) {
        SectionErrorKind.Offline -> "Offline. Check your connection."
        SectionErrorKind.NotAvailable -> "This Library source is not available."
        SectionErrorKind.AppCredential -> "Library service configuration is unavailable."
        SectionErrorKind.SessionExpired -> "Session expired. Verify your session."
        SectionErrorKind.Generic -> "Could not read this Library source."
    } + if (retained) " Loaded entries are retained." else ""
    if (kind.retryable) ErrorState(message, retry) else Text(message)
}
