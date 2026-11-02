package com.techgv.cineverse.ui.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.tmdb.formatRuntime
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.LocalReduceMotion
import com.techgv.cineverse.ui.theme.displayStyle
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

internal val LocalMobileDetailActionObserver = staticCompositionLocalOf<
    ((MobileDetailViewModel, MobileDetailAuthority?, (LibraryKind) -> Unit) -> Unit)?
> { null }

internal fun Detail.mobileDetailVideos(): List<Video> = videos.filter { isYouTubeKey(it.key) }.distinctBy { it.key }

/** The parent binds route lifetime/resume on the owner; composition is not lifecycle authority. */
@Composable
internal fun MobileDetailScreen(
    model: MobileDetailViewModel,
    navigator: MobileNavigator,
    onBack: () -> Unit,
    onShare: ((MediaRef) -> Unit)? = null,
    seedPosterPath: String? = null,
    seedTitle: String? = null,
    seedBackdropPath: String? = null,
) {
    val state = model.state.collectAsState().value
    // One list for every segment: a segment switch swaps only that segment's items, never the header or bar.
    key(model) {
        val segment = state.segment
        val currentSegment by rememberUpdatedState(segment)
        val content = (state.section as? SectionState.Content)?.value
        val topologyReady = content?.ref == model.ref && content != null && !state.stale &&
            !state.settingsFailed && !state.libraryFailed && !state.identityFailed
        // Returning (e.g. Back from Person) recreates this list while the retained copy re-verifies, which waits for
        // the route to resume and a network read; starting at 0 showed the page top for that whole wait, then jumped.
        // A retained copy without a recovery banner has the healthy layout, so the list starts at the saved position.
        // The authoritative restore and persistence below still wait for verification.
        val scroll = remember {
            val retainedLayout = state.retained?.ref == model.ref && model.ref != null && !showsReadRecovery(state)
            if (topologyReady || retainedLayout) model.scroll(segment).let { LazyListState(it.index, it.offset) } else LazyListState()
        }
        // The segment whose saved position has been applied (or deliberately kept); its scroll is persisted.
        var restored by remember { mutableStateOf<MobileDetailSegment?>(null) }
        val persist by rememberUpdatedState(restored == segment && topologyReady)
        LaunchedEffect(scroll, segment, topologyReady) {
            if (!topologyReady || restored == segment) return@LaunchedEffect
            // scrollToItem remeasures the current composed provider without requiring a visible anchor.
            val position = model.scroll(segment)
            scroll.scrollToItem(position.index, position.offset)
            restored = segment
        }
        LaunchedEffect(scroll, segment, restored, topologyReady) {
            // restored moves to the tapped segment in the click, before this segment's collector is cancelled.
            if (persist) snapshotFlow { MobileDetailScroll(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }
                .distinctUntilChanged().collect { if (persist && restored == segment) model.saveScroll(segment, it.index, it.offset) }
        }
        // A tapped segment's open position. The selection reaches composition one dispatch after the click, so it is
        // requested here, in the composition that has the new items; requesting it in the click would apply it to
        // the old segment's items for a frame.
        var pendingOpen by remember { mutableStateOf<Pair<MobileDetailSegment, MobileDetailScroll>?>(null) }
        pendingOpen?.takeIf { it.first == segment }?.let { (_, open) ->
            SideEffect { scroll.requestScrollToItem(open.index, open.offset); pendingOpen = null }
        }
        // From above the bar, a tapped segment scrolls the page up until the bar pins, so its content is on screen
        // (one deliberate glide, instant under reduce motion); the header is not remounted.
        var pendingReveal by remember { mutableStateOf<Pair<MobileDetailSegment, Int>?>(null) }
        val reduceMotion = LocalReduceMotion.current
        LaunchedEffect(pendingReveal, segment) {
            val (_, bar) = pendingReveal?.takeIf { it.first == segment } ?: return@LaunchedEffect
            if (reduceMotion) scroll.scrollToItem(bar) else scroll.animateScrollToItem(bar)
            pendingReveal = null
        }
        DisposableEffect(scroll) {
            onDispose { if (persist) model.saveScroll(currentSegment, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }
        }
        val onAct: (LibraryKind) -> Unit = { model.act(it, state.authority) }
        val actionObserver = LocalMobileDetailActionObserver.current
        if (actionObserver != null) SideEffect { actionObserver(model, state.authority, onAct) }
        // The card's seed only stands in while the first fetch loads; any loaded/retained Detail replaces it.
        val seed = model.ref?.takeIf { seedPosterPath != null || seedTitle != null }
            ?.let { MobileDetailHeaderArt(it, seedPosterPath, seedTitle.orEmpty(), seedBackdropPath) }
        // With the sticky bar pinned, a tapped segment keeps it pinned: it opens at its saved position when that is
        // deeper than the bar, else exactly at the bar. With the bar not pinned (near the page top) it glides to the bar.
        val onSegment: (MobileDetailSegment) -> Unit = { target ->
            if (persist) model.saveScroll(segment, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
            val bar = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "segments" }?.index
            if (bar != null && scroll.firstVisibleItemIndex >= bar) {
                val saved = model.scroll(target)
                val open = segmentOpenScroll(saved, bar) ?: saved
                model.saveScroll(target, open.index, open.offset)
                pendingOpen = target to open
            } else if (bar != null) {
                pendingReveal = target to bar
            }
            model.selectSegment(target)
            if (topologyReady && model.state.value.segment == target) restored = target
        }
        MobileDetailContent(state, seed, model.imageBase, scroll, onSegment, model::setExpanded,
            onAct = onAct, onRetryReads = model::retryReads, onRetryDetail = model::retryDetail,
            onNavigate = { target -> model.browse { navigator.open(target) } }, onBack = { model.browse(onBack) },
            onShare = onShare?.let { share -> { ref -> model.browse { share(ref) } } })
    }
}

/**
 * Returning to Detail re-verifies the title; while that read is in flight the retained copy is "stale" for a moment
 * (actions stay disabled), so the warning appears only for failed preferences or a re-verification that failed.
 */
/** Labels/icons only: the confirmed account state, or the last confirmed one while a return re-verifies. */
internal val MobileDetailState.shownAccount: LibraryState get() = if (account.known) account else lastKnownAccount ?: account

internal fun showsReadRecovery(state: MobileDetailState): Boolean =
    state.settingsFailed || (state.stale && state.section is SectionState.Error)

/** Position a newly selected segment opens at: the sticky bar's index unless the user was already deeper in it. */
internal fun segmentOpenScroll(saved: MobileDetailScroll, barIndex: Int?): MobileDetailScroll? =
    if (barIndex != null && saved.index < barIndex) MobileDetailScroll(barIndex, 0) else null

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileDetailContent(
    state: MobileDetailState,
    seed: MobileDetailHeaderArt?,
    imageBase: String,
    scroll: LazyListState,
    onSegment: (MobileDetailSegment) -> Unit,
    onExpanded: (Boolean) -> Unit,
    onAct: (LibraryKind) -> Unit,
    onRetryReads: () -> Unit,
    onRetryDetail: () -> Unit,
    onNavigate: (MobileDestination) -> Unit,
    onBack: () -> Unit,
    onShare: ((MediaRef) -> Unit)?,
) {
    val detail = (state.section as? SectionState.Content)?.value ?: state.retained
    val palette = mobilePalette(detail?.ref?.key ?: "detail")
    val solid = rememberTopBarSolid(scroll, 250.dp)
    val segments = if (detail?.ref?.mediaType == MediaType.Tv) listOf(MobileDetailSegment.Seasons) + MobileDetailSegment.entries.filter { it != MobileDetailSegment.Seasons }
        else MobileDetailSegment.entries.filter { it != MobileDetailSegment.Seasons }
    val loading = detail == null && state.section == SectionState.Loading
    // One "header" item call site for seed and Detail, so the shared poster is never remounted when data lands.
    val header = if (state.invalid) null else detail?.let { MobileDetailHeaderArt(it.ref, it.posterPath, it.title, it.backdropPath) } ?: seed?.takeIf { loading }
    // Which card this Detail pairs with for shared elements; captured once so Back returns to the same card.
    val origin = header?.let { rememberMobileDetailOrigin(it.ref) }
    val markPerson = rememberMobileSharedOpenPerson()
    val barHeight = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() } + 52.dp
    // "segment-fill" height in px; null = a full viewport, used for the first pass after a segment switch so the
    // list is never clamped (moved) before the new segment's items have been measured.
    val fill = remember(state.segment) { mutableStateOf<Int?>(null) }
    val switchedLayout = remember(state.segment) { Snapshot.withoutReadObservation { scroll.layoutInfo } }
    LaunchedEffect(scroll, state.segment) {
        snapshotFlow { scroll.layoutInfo }.filter { it !== switchedLayout }.collect { fill.value = segmentFill(it, fill.value) }
    }
    Box(Modifier.fillMaxSize()) {
        AmbientGlow(palette)
        // The backdrop sits behind both the bar and the list, so the bar is fully transparent over it until the
        // header scrolls away (then it frosts as before). It follows the header item's scroll offset; the item
        // itself only reserves the 240dp slot. Lazy lists clip their items, so it cannot be drawn from inside.
        // A landscape card's art flies into this backdrop; its cached w780 stands in while w1280 loads.
        if (header != null && origin != null) PaletteArt(imageUrl(imageBase, "w1280", header.backdropPath), mobilePalette(header.ref.key),
            ArtStyle.Backdrop, mobileSharedBackdrop(origin).fillMaxWidth().height(barHeight + 240.dp).testTag("detail-backdrop").graphicsLayer {
                val item = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "header" }
                if (item == null) alpha = 0f else translationY = item.offset.toFloat()
            }, bottomScrim = true, placeholderUrl = imageUrl(imageBase, "w780", seed?.backdropPath))
        Column {
            GlassTopBar(solid, title = detail?.title?.takeIf { solid }, scrim = false,
                navigation = { RoundIconButton(CvIcons.ChevronLeft, "Back", onBack) },
                actions = {
                    if (detail != null) {
                        if (onShare != null) RoundIconButton(CvIcons.Share, "Share title", { onShare(detail.ref) })
                        val shown = state.shownAccount
                        RoundIconButton(if (shown.favourite) CvIcons.HeartFilled else CvIcons.Heart, "Favourite title",
                            { onAct(LibraryKind.FAVOURITE) }, modifier = Modifier.semantics {
                                stateDescription = when {
                                    !shown.known -> "Favourite unknown"
                                    shown.favourite -> "Favourite"
                                    else -> "Not favourite"
                                }
                            }, content = if (shown.favourite) Tokens.Colors.Accent else Color.White,
                            enabled = state.authority != null && state.account.canToggle(LibraryKind.FAVOURITE))
                    }
                })
            LazyColumn(Modifier.fillMaxSize().testTag("mobile-detail"), state = scroll,
                contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (state.invalid) item("invalid") {
                    Text("Invalid title. Go back and choose a movie or TV show.", Modifier.padding(20.dp))
                } else {
                    if (showsReadRecovery(state)) item("read-recovery") {
                        Column(Modifier.padding(horizontal = 20.dp).testTag("detail-read-recovery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.stale) Text("Previously loaded title — unverified. Actions disabled.")
                            ErrorState(if (state.settingsFailed) "Saved preferences unavailable. Retry reads." else "Verify this title before changing your library.", onRetryReads)
                        }
                    }
                    if (state.identityFailed || state.libraryFailed) item("account-recovery") {
                        ErrorState(if (state.identityFailed) "Account identity unavailable. Private library hidden."
                            else "Library unavailable. Retry reads; changes are not replayed.", onRetryReads,
                            Modifier.padding(horizontal = 20.dp).testTag("detail-account-recovery"))
                    }
                    if (state.section is SectionState.Error) item("catalog-error") {
                        Column(Modifier.padding(horizontal = 20.dp)) { SectionFailure(state.section.kind, onRetryDetail) }
                    }
                    if (header != null || loading) item("header") { DetailHeader(header, origin, detail, imageBase, onNavigate) }
                    if (loading) item("loading") {
                        // Shimmer shaped like the summary, actions and segment bar; one announced label instead of visible text.
                        Column(Modifier.padding(horizontal = 20.dp).clearAndSetSemantics {
                            contentDescription = "Loading title…"; liveRegion = LiveRegionMode.Polite
                        }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).shimmer())
                            SkeletonLines(3)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                repeat(3) { Box(Modifier.weight(1f).height(66.dp).shimmer(RoundedCornerShape(18.dp))) }
                            }
                            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(36.dp).shimmer(RoundedCornerShape(14.dp)))
                        }
                    }
                    if (detail != null) {
                        item("summary") {
                            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (detail.tagline.isNotBlank()) Text(detail.tagline + if (detail.taglineInEnglish) " (English)" else "", fontStyle = FontStyle.Italic)
                                if (detail.overview.isNotBlank()) {
                                    var overflows by remember(detail.overview) { mutableStateOf(false) }
                                    Text(detail.overview + if (detail.overviewInEnglish) " (English)" else "",
                                        Modifier.animateContentSize(if (LocalReduceMotion.current) snap() else spring()),
                                        maxLines = if (state.expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                                        onTextLayout = { if (!state.expanded) overflows = it.hasVisualOverflow })
                                    if (overflows || state.expanded) PillButton(if (state.expanded) "Read less" else "Read more",
                                        { onExpanded(!state.expanded) }, variant = ButtonVariant.Ghost, small = true)
                                }
                                DetailActions(detail, state, onAct, onNavigate)
                            }
                        }
                        stickyHeader("segments") {
                            SegmentedControl(segments.map { it.name }, segments.indexOf(state.segment).coerceAtLeast(0), { onSegment(segments[it]) },
                                Modifier.fillMaxWidth().background(Tokens.Colors.Background).padding(horizontal = 20.dp, vertical = 8.dp).testTag("detail-segments"), compact = true)
                        }
                        when (state.segment) {
                            MobileDetailSegment.Overview -> {
                                if (detail.facts.isNotEmpty()) item("facts") {
                                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        detail.facts.chunked(2).forEach { pair ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                pair.forEach { FactTile(it.label, it.value, Modifier.weight(1f)) }
                                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                                if (detail.cast.any { it.personId > 0 }) item("top-cast") {
                                    ScrollRow("Top billed cast") {
                                        items(detail.cast.filter { it.personId > 0 }, key = { it.personId }) { person ->
                                            PersonCircle(person.name, person.role.takeIf { it.isNotBlank() }, mobilePalette("person:${person.personId}"),
                                                { markPerson("detail-top-cast", person.personId); onNavigate(person.destination()) },
                                                imageUrl = imageUrl(imageBase, "w185", person.profilePath),
                                                artModifier = mobileSharedAvatar(person.personId, "detail-top-cast"))
                                        }
                                    }
                                }
                                if (detail.recommendations.any { it.ref.id > 0 }) item("recommendations") {
                                    ScrollRow("More like this") {
                                        items(detail.recommendations.filter { it.ref.id > 0 }, key = { it.ref.key }) { title ->
                                            PosterCard(title.title, titleMeta(title), mobilePalette(title.ref.key),
                                                { onNavigate(MobileDestination.Detail(title.ref)) },
                                                imageUrl = imageUrl(imageBase, "w342", title.posterPath), voteAverage = title.voteAverage, voteCount = title.voteCount)
                                        }
                                    }
                                }
                            }
                            MobileDetailSegment.Cast -> {
                                val cast = detail.cast.filter { it.personId > 0 }
                                if (cast.isEmpty()) item("no-cast") { DetailEmpty("No cast available.") }
                                items(cast, key = { "cast:${it.personId}" }) { person ->
                                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("detail-person-${person.personId}")
                                        .cvClickable({ markPerson("detail-cast", person.personId); onNavigate(person.destination()) }),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        PaletteArt(imageUrl(imageBase, "w185", person.profilePath), mobilePalette("person:${person.personId}"), ArtStyle.Person,
                                            mobileSharedAvatar(person.personId, "detail-cast").size(52.dp).clip(RoundedCornerShape(26.dp)))
                                        Column(Modifier.weight(1f)) { Text(person.name); if (person.role.isNotBlank()) Text(person.role) }
                                        Text("›")
                                    }
                                }
                            }
                            MobileDetailSegment.Videos -> {
                                val videos = detail.mobileDetailVideos()
                                if (videos.isEmpty()) item("no-videos") { DetailEmpty("No videos available.") }
                                items(videos, key = { "video:${it.key}" }) { video ->
                                    VideoCard(video.name, video.type.takeIf { it.isNotBlank() }, palette,
                                        { onNavigate(MobileDestination.Trailer(video.key, detail.title, video.name)) },
                                        Modifier.padding(horizontal = 20.dp).testTag("detail-video-${video.key}"), width = 350.dp)
                                }
                            }
                            MobileDetailSegment.Reviews -> {
                                if (detail.reviews.isEmpty()) item("no-reviews") { DetailEmpty("No reviews available.") }
                                items(detail.reviews, key = { "review:${it.id}" }) { review ->
                                    // The domain review has no date; do not invent one for the kit's date slot.
                                    ReviewCard(review.author, "", review.rating, review.text, Modifier.padding(horizontal = 20.dp))
                                }
                            }
                            MobileDetailSegment.Seasons -> {
                                val seasons = detail.seasons.filter { it.seasonNumber >= 0 }
                                if (seasons.isEmpty()) item("no-seasons") { DetailEmpty("No seasons available.") }
                                items(seasons, key = { "season:${it.seasonNumber}" }) { season ->
                                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("detail-season-${season.seasonNumber}")
                                        .cvClickable({ onNavigate(MobileDestination.Season(detail.ref.id, season.seasonNumber)) })
                                        .clip(RoundedCornerShape(18.dp)).background(Tokens.Colors.Tile).padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        PaletteArt(imageUrl(imageBase, "w185", season.posterPath), palette, ArtStyle.Poster,
                                            Modifier.size(70.dp, 105.dp).clip(RoundedCornerShape(10.dp)))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(season.label, style = displayStyle(20.sp))
                                            if (season.name.isNotBlank() && season.name != season.label) Text(season.name)
                                            val metadata = listOfNotNull(season.episodeCount.takeIf { it > 0 }?.let { "$it episodes" }, season.airDate?.takeIf { it.isNotBlank() })
                                            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "))
                                        }
                                        Text("›")
                                    }
                                }
                            }
                        }
                        item("segment-fill") {
                            val height = fill.value
                            Spacer(if (height == null) Modifier.fillParentMaxHeight() else Modifier.height(with(LocalDensity.current) { height.toDp() }))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Height for the trailing "segment-fill" spacer: tops a short segment up so the sticky bar can stay pinned at the
 * list top. It never shrinks below what keeps the current position reachable (so it cannot clamp the list) and
 * never grows past [current] while scrolling (null = a full viewport, right after a switch). Zero once the segment
 * fills the viewport below the pinned bar; [current] is kept while that cannot be decided from [info].
 */
internal fun segmentFill(info: LazyListLayoutInfo, current: Int?): Int? {
    val items = info.visibleItemsInfo
    val bar = items.firstOrNull { it.key == "segments" } ?: return current
    val pinned = items.none { it.index < bar.index }
    val end = items.firstOrNull { it.key == "segment-fill" } ?: return if (pinned) 0 else current
    val viewport = info.viewportSize.height - info.afterContentPadding
    val between = items.filter { it.index > bar.index && it.index < end.index }
    // Exact only when every segment item is laid out; otherwise some scrolled off the top, so the content is long.
    val pinnable = if (between.size != end.index - bar.index - 1) 0
        else viewport - bar.size - between.sumOf { it.size } - info.mainAxisItemSpacing * (between.size + 1)
    val keepsPosition = viewport - end.offset
    return maxOf(0, pinnable, if (current == null) keepsPosition else minOf(current, keepsPosition))
}

/** Seeds Person's header with the cast card's photo and name so its shared avatar exists on Person's first frame. */
private fun CastCredit.destination() = MobileDestination.Person(personId, name, profilePath)

/** What the header's shared poster and title show: the opening card's seed until [Detail] loads, then Detail's own. */
private data class MobileDetailHeaderArt(val ref: MediaRef, val posterPath: String?, val title: String, val backdropPath: String? = null)

/** [art] null = no card seed (deep link) and still loading: skeleton poster and title; [detail] null = skeleton metadata. */
@Composable
private fun DetailHeader(art: MobileDetailHeaderArt?, origin: String?, detail: Detail?, imageBase: String, onNavigate: (MobileDestination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy((-60).dp)) {
        Spacer(Modifier.fillMaxWidth().height(240.dp)) // backdrop slot; the image is drawn behind the list

        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            if (art == null || origin == null) Box(Modifier.size(116.dp, 174.dp).shimmer(RoundedCornerShape(18.dp)).testTag("detail-poster-skeleton"))
            else PaletteArt(imageUrl(imageBase, "w342", art.posterPath), mobilePalette(art.ref.key), ArtStyle.Poster,
                mobileSharedPoster(origin).size(116.dp, 174.dp).clip(RoundedCornerShape(18.dp)).testTag("detail-poster"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (art == null || origin == null) Box(Modifier.fillMaxWidth(0.8f).height(28.dp).shimmer())
                else Text(art.title, mobileSharedTitle(origin), style = displayStyle(27.sp))
                if (detail == null) {
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).shimmer())
                    Box(Modifier.fillMaxWidth(0.85f).height(14.dp).shimmer())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).shimmer(CircleShape))
                        Box(Modifier.width(72.dp).height(12.dp).shimmer())
                    }
                    Box(Modifier.fillMaxWidth(0.7f).height(14.dp).shimmer())
                } else {
                    val metadata = listOfNotNull(detail.year, detail.certification?.takeIf { it.isNotBlank() }, detail.runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime))
                    if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "))
                    if (detail.genres.isNotEmpty()) Text(detail.genres.joinToString(", "))
                    if (detail.voteCount > 0) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ScoreRing(detail.voteAverage, detail.voteCount)
                        Text("User score")
                    }
                    detail.leadCrew?.takeIf { it.personId > 0 }?.let { person ->
                        Text("${person.role}: ${person.name}", Modifier.cvClickable({ onNavigate(MobileDestination.Person(person.personId)) }))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailActions(detail: Detail, state: MobileDetailState, onAct: (LibraryKind) -> Unit, onNavigate: (MobileDestination) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val account = state.shownAccount
        val rating = account.rating
        ActionTile(when {
            LibraryKind.WATCHLIST in account.pending -> "Saving…"
            !account.known -> "Watchlist unknown"
            account.watchlist -> "Saved"
            else -> "Watchlist"
        }, if (account.watchlist) CvIcons.Check else CvIcons.Plus, { onAct(LibraryKind.WATCHLIST) },
            Modifier.weight(1f).testTag("detail-watchlist"),
            container = if (account.watchlist) Tokens.Colors.Accent.copy(alpha = 0.22f) else Tokens.Colors.Tile,
            enabled = state.authority != null && state.account.canToggle(LibraryKind.WATCHLIST))
        ActionTile(when {
            LibraryKind.RATING in account.pending -> "Saving…"
            !account.known -> "Rating unknown"
            rating != null -> "★ ${oneDecimal(rating)}"
            else -> "Rate"
        }, CvIcons.Star, { onAct(LibraryKind.RATING) }, Modifier.weight(1f).testTag("detail-rate"),
            content = if (rating != null) Tokens.Colors.Gold else Tokens.Colors.TextPrimary,
            enabled = state.authority != null && state.account.canToggle(LibraryKind.RATING))
        detail.mobileDetailVideos().firstOrNull()?.let { video ->
            ActionTile("Trailer", CvIcons.Play, { onNavigate(MobileDestination.Trailer(video.key, detail.title, video.name)) },
                Modifier.weight(1f).testTag("detail-trailer"))
        }
    }
}

@Composable
private fun DetailEmpty(message: String) { Text(message, Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
