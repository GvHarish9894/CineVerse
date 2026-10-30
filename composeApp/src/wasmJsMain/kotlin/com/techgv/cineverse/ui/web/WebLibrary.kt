package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.formatDate
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
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
        WebPage {
            WebPageTitle("My Library")
            Text("This Library entry has invalid saved state. It was not changed.", Modifier.padding(top = 24.dp).testTag("library-invalid"), style = bodyStyle(15.sp))
        }
        return
    }
    val ready = session as? WebSessionState.Ready
    if (ready == null) {
        WebPage {
            WebPageTitle("My Library")
            LibraryNotice(if (session is WebSessionState.Failed) "Library identity could not be verified. Saved data is unchanged." else "Verifying Library identity…",
                Modifier.padding(top = 24.dp)) { PillButton("Verify session", runtime::revalidateSession, variant = ButtonVariant.Ghost, small = true) }
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
        owner::loadMore, owner::retry, owner::retryStorage, runtime::revalidateSession)
}

/** One page column: title, tabs, then Movies and TV Shows sections (or one tab empty state) and exactly one Footer. */
@Composable
internal fun WebLibraryContent(
    entry: WebEntry,
    state: LibraryUiState?,
    pages: Map<MediaType, Int?>,
    storage: BrowserStorageFailure?,
    navigate: (WebRoute) -> Unit,
    loadMore: (MediaType) -> Unit,
    retry: (MediaType) -> Unit,
    retryStorage: () -> Unit,
    verifySession: () -> Unit,
) {
    val restored = remember(entry) { validateWebLibraryEntry(entry) }
    val scroll = remember(entry) { LazyGridState() }
    var positioned by remember(entry) { mutableStateOf(false) }
    // Only restore after both groups settle at their bounded saved pages. An error stops here until an explicit retry.
    LaunchedEffect(state, pages, positioned) {
        if (state == null || positioned) return@LaunchedEffect
        for (media in MediaType.entries) {
            val group = state.group(media)
            val page = pages.getValue(media)
            if ((state.signedIn && page == null) || group.loadingMore || group.moreError != null || group.refreshError != null) return@LaunchedEffect
            if (group.section !is SectionState.Content && group.section != SectionState.Empty) return@LaunchedEffect
            if (page != null && page < restored.positions.getValue(media).page && group.hasMore && page < 500) { loadMore(media); return@LaunchedEffect }
        }
        // The single page scroll lives under the movie keys; library.tv.index/offset stay validated for old history but are never written.
        val target = restored.positions.getValue(MediaType.Movie)
        scroll.scrollToItem(target.index, target.offset)
        positioned = true
    }
    LaunchedEffect(scroll, positioned) {
        if (positioned) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            entry.putInt(MediaType.Movie.libraryKey("index"), index)
            entry.putInt(MediaType.Movie.libraryKey("offset"), offset)
        }
    }
    LaunchedEffect(pages, positioned) {
        if (positioned) pages.forEach { (media, page) -> if (page != null) entry.putInt(media.libraryKey("page"), page) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = contentGutter(ShellStyle.Web, maxWidth)
        val phone = maxWidth <= 640.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (phone) 140.dp else 170.dp), state = scroll,
            modifier = Modifier.fillMaxSize().testTag("library-grid"),
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(if (phone) 16.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(if (phone) 24.dp else 34.dp),
        ) {
            fullWidth {
                WebPageTitle("My Library", subtitle = when {
                    state == null -> null
                    state.signedIn -> "Synced with your TMDB account"
                    else -> "Saved in this browser · sign in to sync with TMDB"
                })
            }
            fullWidth {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryTab.entries.forEach { tab ->
                        val count = if (state?.tab == tab) libraryKnownCount(state) else null
                        Chip(tab.libraryLabel() + (count?.let { " · $it" } ?: ""), tab == restored.tab, { navigate(tab.libraryRoute()) })
                    }
                }
            }
            if (storage != null) fullWidth { WebStorageNotice(storage, retryStorage, Modifier.testTag("library-storage-recovery")) }
            if (state == null) {
                fullWidth {
                    LibraryNotice("Library source is not yet verified. No private entries are shown.") {
                        PillButton("Verify session", verifySession, variant = ButtonVariant.Ghost, small = true)
                        if (storage == null) PillButton("Retry saved reads", retryStorage, variant = ButtonVariant.Ghost, small = true)
                    }
                }
            } else {
                if (MediaType.entries.any { media ->
                    val group = state.group(media)
                    (group.section as? SectionState.Error)?.kind == SectionErrorKind.SessionExpired || group.moreError == SectionErrorKind.SessionExpired || group.refreshError == SectionErrorKind.SessionExpired
                }) fullWidth { LibraryNotice("Session expired.") { PillButton("Verify session", verifySession, variant = ButtonVariant.Ghost, small = true) } }
                if (state.movies.section == SectionState.Empty && state.tv.section == SectionState.Empty) fullWidth {
                    EmptyState(when (state.tab) {
                        LibraryTab.Watchlist -> "Your watchlist is empty"
                        LibraryTab.Favourites -> "No favourites yet"
                        LibraryTab.Rated -> "Nothing rated yet"
                    }, "Use the bookmark, heart or star on any title and it will show up here.") {
                        PillButton("Browse popular movies", { navigate(WebRoute.Browse("movie")) })
                    }
                } else MediaType.entries.forEach { media ->
                    librarySection(media, state.group(media), state.tab, pages.getValue(media), { loadMore(media) }, { retry(media) }, navigate)
                }
            }
            // Footer() computes its own gutter from the full width, so undo the grid's content padding for it.
            fullWidth { Footer(Modifier.padding(top = 56.dp).bleed(gutter)) }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable LazyGridItemScope.() -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }, content = content)

private fun Modifier.bleed(gutter: Dp) = layout { measurable, constraints ->
    val extra = gutter.roundToPx()
    val width = constraints.maxWidth + 2 * extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

private fun LibraryTab.libraryLabel() = if (this == LibraryTab.Rated) "Ratings" else label

/** Tab count only when it is a known total: both groups loaded, nothing more to page, nothing stale. */
internal fun libraryKnownCount(state: LibraryUiState): Int? {
    fun LibraryGroup.count(): Int? = if (hasMore || refreshError != null) null else when (val value = section) {
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

private fun LazyGridScope.librarySection(
    media: MediaType, group: LibraryGroup, tab: LibraryTab, page: Int?,
    loadMore: () -> Unit, retry: () -> Unit, navigate: (WebRoute) -> Unit,
) {
    val label = if (media == MediaType.Movie) "Movies" else "TV Shows"
    fullWidth { WebSectionHeader(label, Modifier.padding(top = 20.dp)) }
    when (val section = group.section) {
        SectionState.Loading -> items(6) { PosterCardSkeleton(Modifier.fillMaxWidth(), width = Dp.Unspecified) }
        SectionState.Empty -> fullWidth {
            val noun = if (media == MediaType.Movie) "movies" else "TV shows"
            LibraryNotice(when (tab) {
                LibraryTab.Watchlist -> "No $noun in your watchlist yet."
                LibraryTab.Favourites -> "No $noun in your favourites yet."
                LibraryTab.Rated -> "No rated $noun yet."
            }) {
                PillButton(if (media == MediaType.Movie) "Browse popular movies" else "Browse popular TV shows",
                    { navigate(WebRoute.Browse(if (media == MediaType.Movie) "movie" else "tv")) }, variant = ButtonVariant.Ghost, small = true)
            }
        }
        is SectionState.Error -> fullWidth { WebLibraryError(section.kind, retry, false) }
        is SectionState.Content -> items(section.value, key = { "${it.title.ref.mediaType}:${it.title.ref.id}" }) { item ->
            BoxWithConstraints {
                PosterCard(item.title.title, formatDate(item.title.releaseDate), ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary),
                    { navigate(item.title.ref.libraryDetailRoute()) }, Modifier.testTag("library-${item.title.ref.mediaType}-${item.title.ref.id}"),
                    imageUrl = webImage(item.title.posterPath, "w342"), voteAverage = item.title.voteAverage, voteCount = item.title.voteCount, width = maxWidth,
                    badge = if (tab == LibraryTab.Rated) item.rating?.let { "★ ${oneDecimal(it)}" } else null)
            }
        }
    }
    fullWidth {
        Column(Modifier.testTag("library-${media.name}-paging"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            group.refreshError?.let { WebLibraryError(it, retry, true) }
            group.moreError?.let { WebLibraryError(it, retry, true) }
            if (group.loadingMore) Text("Loading more $label…", style = bodyStyle(14.sp), color = Tokens.Colors.TextSecondary)
            else if (group.hasMore && page != null && page < 500 && group.moreError == null && group.refreshError == null) PillButton("Load more $label", loadMore, variant = ButtonVariant.Ghost)
            if (page == 500 && group.hasMore) Text("Reached the 500-page limit.", style = bodyStyle(14.sp), color = Tokens.Colors.TextSecondary)
        }
    }
}

/** Neutral gutter notice (`.panel` surface) with its actions inline; errors keep ErrorState. */
@Composable
private fun LibraryNotice(message: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    FlowRow(
        modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = bodyStyle(15.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.85f))
        actions()
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
    if (kind.retryable) ErrorState(message, retry) else Text(message, style = bodyStyle(15.sp), color = Tokens.Colors.TextSecondary)
}
