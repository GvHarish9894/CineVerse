package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.tmdb.formatRuntime
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.displayStyle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

private val MobileYouTubeKey = Regex("[A-Za-z0-9_-]{11}")
internal fun Detail.mobileDetailVideos(): List<Video> = videos.filter { MobileYouTubeKey.matches(it.key) }.distinctBy { it.key }

/** The parent binds route lifetime/resume on the owner; composition is not lifecycle authority. */
@Composable
internal fun MobileDetailScreen(
    model: MobileDetailViewModel,
    navigator: MobileNavigator,
    onBack: () -> Unit,
    onShare: ((MediaRef) -> Unit)? = null,
) {
    val state by model.state.collectAsState()
    key(model, state.segment) {
        val segment = state.segment
        val position = remember { model.scroll(segment) }
        val scroll = remember { LazyListState() }
        var restored by remember { mutableStateOf(false) }
        val content = (state.section as? SectionState.Content)?.value
        val topologyReady = content?.ref == model.ref && content != null && !state.stale &&
            !state.settingsFailed && !state.libraryFailed && !state.identityFailed
        val persist by rememberUpdatedState(restored && topologyReady)
        LaunchedEffect(scroll, topologyReady) {
            if (!topologyReady || restored) return@LaunchedEffect
            // Loading/error/retained rows must not consume the target before the real layout exists.
            snapshotFlow { scroll.layoutInfo.visibleItemsInfo.any { it.key == "header" } }.first { it }
            scroll.scrollToItem(position.index, position.offset)
            restored = true
        }
        LaunchedEffect(scroll, restored, topologyReady) {
            if (persist) snapshotFlow { MobileDetailScroll(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }
                .distinctUntilChanged().collect { if (persist) model.saveScroll(segment, it.index, it.offset) }
        }
        DisposableEffect(scroll) {
            onDispose { if (persist) model.saveScroll(segment, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }
        }
        MobileDetailContent(state, model.imageBase, scroll, model::selectSegment, model::setExpanded,
            onAct = { model.act(it, state.authority) }, onRetryReads = model::retryReads, onRetryDetail = model::retryDetail,
            onNavigate = { target -> model.browse { navigator.open(target) } }, onBack = { model.browse(onBack) },
            onShare = onShare?.let { share -> { ref -> model.browse { share(ref) } } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileDetailContent(
    state: MobileDetailState,
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
    Box(Modifier.fillMaxSize()) {
        AmbientGlow(palette)
        Column {
            GlassTopBar(solid, title = detail?.title?.takeIf { solid },
                navigation = { RoundIconButton(CvIcons.ChevronLeft, "Back", onBack) },
                actions = {
                    if (detail != null) {
                        if (onShare != null) RoundIconButton(CvIcons.Share, "Share title", { onShare(detail.ref) })
                        RoundIconButton(if (state.account.favourite) CvIcons.HeartFilled else CvIcons.Heart, "Favourite title",
                            { onAct(LibraryKind.FAVOURITE) }, modifier = Modifier.semantics {
                                stateDescription = when {
                                    !state.account.known -> "Favourite unknown"
                                    state.account.favourite -> "Favourite"
                                    else -> "Not favourite"
                                }
                            }, content = if (state.account.favourite) Tokens.Colors.Accent else Color.White,
                            enabled = state.authority != null && state.account.canToggle(LibraryKind.FAVOURITE))
                    }
                })
            LazyColumn(Modifier.fillMaxSize().testTag("mobile-detail"), state = scroll,
                contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (state.invalid) item("invalid") {
                    Text("Invalid title. Go back and choose a movie or TV show.", Modifier.padding(20.dp))
                } else {
                    if (state.settingsFailed || state.stale) item("read-recovery") {
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
                    if (detail == null && state.section == SectionState.Loading) item("loading") {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PosterCardSkeleton()
                            Text("Loading title…")
                        }
                    }
                    if (detail != null) {
                        item("header") { DetailHeader(detail, imageBase, onNavigate) }
                        item("summary") {
                            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (detail.tagline.isNotBlank()) Text(detail.tagline + if (detail.taglineInEnglish) " (English)" else "", fontStyle = FontStyle.Italic)
                                if (detail.overview.isNotBlank()) {
                                    var overflows by remember(detail.overview) { mutableStateOf(false) }
                                    Text(detail.overview + if (detail.overviewInEnglish) " (English)" else "",
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
                                                { onNavigate(MobileDestination.Person(person.personId)) },
                                                imageUrl = imageUrl(imageBase, "w185", person.profilePath))
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
                                        .cvClickable({ onNavigate(MobileDestination.Person(person.personId)) }),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        PaletteArt(imageUrl(imageBase, "w185", person.profilePath), mobilePalette("person:${person.personId}"), ArtStyle.Person,
                                            Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)))
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
                        item("attribution") { Footer() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(detail: Detail, imageBase: String, onNavigate: (MobileDestination) -> Unit) {
    val palette = mobilePalette(detail.ref.key)
    Column(verticalArrangement = Arrangement.spacedBy((-60).dp)) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            PaletteArt(imageUrl(imageBase, "w1280", detail.backdropPath), palette, ArtStyle.Backdrop, Modifier.matchParentSize(), bottomScrim = true)
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            PaletteArt(imageUrl(imageBase, "w342", detail.posterPath), palette, ArtStyle.Poster,
                Modifier.size(116.dp, 174.dp).clip(RoundedCornerShape(18.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(detail.title, style = displayStyle(27.sp))
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

@Composable
private fun DetailActions(detail: Detail, state: MobileDetailState, onAct: (LibraryKind) -> Unit, onNavigate: (MobileDestination) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val account = state.account
        val rating = account.rating
        ActionTile(when {
            LibraryKind.WATCHLIST in account.pending -> "Saving…"
            !account.known -> "Watchlist unknown"
            account.watchlist -> "Saved"
            else -> "Watchlist"
        }, if (account.watchlist) CvIcons.Check else CvIcons.Plus, { onAct(LibraryKind.WATCHLIST) },
            Modifier.weight(1f).testTag("detail-watchlist"),
            container = if (account.watchlist) Tokens.Colors.Accent.copy(alpha = 0.22f) else Tokens.Colors.Tile,
            enabled = state.authority != null && account.canToggle(LibraryKind.WATCHLIST))
        ActionTile(when {
            LibraryKind.RATING in account.pending -> "Saving…"
            !account.known -> "Rating unknown"
            rating != null -> "★ ${oneDecimal(rating)}"
            else -> "Rate"
        }, CvIcons.Star, { onAct(LibraryKind.RATING) }, Modifier.weight(1f).testTag("detail-rate"),
            content = if (rating != null) Tokens.Colors.Gold else Tokens.Colors.TextPrimary,
            enabled = state.authority != null && account.canToggle(LibraryKind.RATING))
        detail.mobileDetailVideos().firstOrNull()?.let { video ->
            ActionTile("Trailer", CvIcons.Play, { onNavigate(MobileDestination.Trailer(video.key, detail.title, video.name)) },
                Modifier.weight(1f).testTag("detail-trailer"))
        }
    }
}

@Composable
private fun DetailEmpty(message: String) { Text(message, Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
