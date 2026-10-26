package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.formatDate
import com.techgv.cineverse.data.tmdb.yearOf
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive

/**
 * Disconnected screen. Root supplies strict raw settings and forwards every generation/session/focus
 * invalidation via contextRevision, plus real runtime recovery/reporting and navigation/back callbacks.
 * A changed revision restarts the ACTUAL raw reader; it is not a freshness label.
 */
@Composable
fun WebPerson(
    entry: WebEntry,
    catalog: CatalogRepository,
    generation: GenerationCounter,
    settings: Flow<Settings>,
    storage: StateFlow<BrowserStorageFailure?>,
    contextRevision: Long,
    retryUpstreamReads: () -> Unit,
    reportStorageFailure: (StorageException) -> Unit,
    navigate: (MediaRef) -> Unit,
    back: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val validated = remember(entry) {
        try { validateWebPersonEntry(entry) } catch (_: IllegalArgumentException) { null }
    }
    if (validated == null) {
        val snapshot = remember(entry) { generation.current }
        Column(Modifier.padding(24.dp)) {
            Text("Invalid person route or saved state. Saved state was not changed.")
            PillButton("Back", { if (scope.isActive && generation.isCurrent(snapshot)) back() })
        }
        return
    }
    // Updated callbacks avoid retaining a superseded root navigation/session closure.
    val currentNavigate by rememberUpdatedState(navigate)
    val currentBack by rememberUpdatedState(back)
    val currentRetry by rememberUpdatedState(retryUpstreamReads)
    val currentReport by rememberUpdatedState(reportStorageFailure)
    val owner = remember(entry, catalog, generation, settings, storage) {
        WebPersonOwner(entry, catalog, generation, settings, storage, { currentRetry() }, { currentReport(it) },
            { currentNavigate(it) }, { currentBack() }, scope)
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    var previousRevision by remember(owner) { mutableStateOf(contextRevision) }
    LaunchedEffect(owner, contextRevision) {
        if (previousRevision != contextRevision) { previousRevision = contextRevision; owner.invalidateContext() }
    }
    val state by owner.state.collectAsState()
    val failure by storage.collectAsState()
    WebPersonContent(owner.validated, state, failure, owner::retryReads, owner::retryPerson,
        owner::toggleBiography, owner::openTitle, owner::back, owner::saveScroll)
}

@Composable
internal fun WebPersonContent(
    entry: WebPersonEntry,
    state: WebPersonReadState,
    storage: BrowserStorageFailure?,
    retryReads: () -> Unit,
    retryPerson: (WebPersonReadState) -> Unit,
    toggleBiography: (WebPersonReadState) -> Unit,
    navigate: (WebPersonReadState, MediaRef) -> Unit,
    back: (WebPersonReadState) -> Unit,
    saveScroll: (WebPersonReadState, Int, Int) -> Unit,
) {
    val scroll = remember(entry) { LazyListState() }
    var restored by remember(entry) { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)
    val currentSave by rememberUpdatedState(saveScroll)
    val person = state.displayed?.takeIf { it.id == entry.id }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val desktop = this.maxWidth > 860.dp
        // Desktop renders known-for and credits inside the right column (one profile item); narrow keeps one item per credit.
        val itemCount = person?.let { if (desktop) 3 else it.credits.size + 5 }
        LaunchedEffect(scroll, person != null, state.verified) {
            if (person == null || !state.verified || restored || itemCount == null) return@LaunchedEffect
            // Wait for the real list, not the single loading/error row, before consuming the saved target.
            snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it == itemCount }
            scroll.scrollToItem(entry.index.coerceAtMost(itemCount - 1), entry.offset)
            restored = true
        }
        LaunchedEffect(scroll, restored) {
            if (restored) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                .distinctUntilChanged().collect { (index, offset) -> currentSave(currentState, index, offset) }
        }
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("web-person")
            .semantics { contentDescription = "Person content: " + if (state.verified) "verified instance" else "unverified" },
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item("status") {
                WebPage { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WebBackLink("Back", { back(state) }, Modifier.padding(top = 12.dp))
                    if (storage != null) WebStorageNotice(storage, retryReads, Modifier.testTag("person-storage-recovery"))
                    if (state.reader == WebPersonReader.Failed) ErrorState(
                        "Saved preferences could not be read. Person content is unverified; saved data was not changed.",
                        retryReads, Modifier.testTag("person-settings-recovery"))
                    if (person != null && !state.verified) Text("Previously loaded person · stale / unverified until replacement content completes.")
                    when (val section = state.section) {
                        SectionState.Loading -> (if (state.reader == WebPersonReader.Unknown) "Verifying saved preferences…" else "Loading person…")
                            .let { if (person == null) WebPersonSkeleton(it) else Text(it) }
                        SectionState.Empty -> EmptyState("Person unavailable", "No person was returned.")
                        is SectionState.Error -> WebPersonError(section.kind) { retryPerson(state) }
                        is SectionState.Content -> Unit
                    }
                } }
            }
            if (person != null) {
                val knownFor = @Composable { WebPersonKnownFor(person, state, navigate) }
                val creditRow = @Composable { index: Int, credit: PersonCredit ->
                    WebPersonCreditRow(credit, index == 0, index == person.credits.lastIndex, state.verified) { navigate(state, credit.ref) }
                }
                item("profile") {
                    WebPage {
                        if (desktop) Row(Modifier.fillMaxWidth().testTag("person-desktop"), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                            WebPersonInfo(person, Modifier.width(300.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                                WebPersonBiography(person, state, toggleBiography, Modifier.fillMaxWidth())
                                knownFor()
                                Column {
                                    WebPersonCreditsHeading(person)
                                    // shortcut: credits compose eagerly in the right column; window them if very long filmographies get slow.
                                    person.credits.forEachIndexed { index, credit -> key(credit.ref.key) { creditRow(index, credit) } }
                                }
                            }
                        } else Column(Modifier.fillMaxWidth().testTag("person-narrow"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            WebPersonInfo(person, Modifier.widthIn(max = 300.dp))
                            WebPersonBiography(person, state, toggleBiography, Modifier.fillMaxWidth())
                        }
                    }
                }
                if (!desktop) {
                    item("known-for") { WebPage { knownFor() } }
                    item("credits-heading") { WebPage { WebPersonCreditsHeading(person) } }
                    itemsIndexed(person.credits, key = { _, credit -> credit.ref.key }) { index, credit ->
                        WebPage { creditRow(index, credit) }
                    }
                }
                item("attribution") { WebPage { Text(TMDB_ATTRIBUTION, style = bodyStyle(12.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.55f)) } }
            }
        }
    }
}

private val personPalette = ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary)

@Composable
private fun WebPersonSkeleton(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Box(Modifier.width(180.dp).aspectRatio(2f / 3f).shimmer(RoundedCornerShape(22.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(48.dp).shimmer(RoundedCornerShape(12.dp)))
            SkeletonLines(4, Modifier.widthIn(max = 820.dp))
        }
    }
}

@Composable
private fun WebPersonInfo(person: PersonDetail, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val shape = RoundedCornerShape(22.dp)
        PaletteArt(webImage(person.profilePath, "w342"), personPalette, ArtStyle.Person,
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .dropShadow(shape, Shadow(60.dp, personPalette.c1.copy(alpha = 0.5f), (-26).dp, DpOffset(0.dp, 40.dp))).clip(shape)
                .semantics { contentDescription = "Portrait of ${person.name.ifBlank { "unknown person" }}" })
        // Only facts the person record actually carries; a missing deathday is not a row.
        val facts = listOfNotNull(
            person.department?.takeIf { it.isNotBlank() }?.let { "Known for" to it },
            person.creditCount.takeIf { it > 0 }?.let { "Credits" to it.toString() },
            person.birthday?.let { "Birthday" to (formatDate(it) ?: it) },
            person.deathday?.let { "Died" to (formatDate(it) ?: it) },
            person.placeOfBirth?.takeIf { it.isNotBlank() }?.let { "Place of birth" to it },
        ).filter { it.second.isNotBlank() }
        Panel(Modifier.fillMaxWidth()) {
            Text("Personal info", Modifier.padding(bottom = 16.dp).semantics { heading() }, style = displayStyle(18.sp))
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                facts.forEach { (label, value) -> FactTile(label, value) }
                if (facts.isEmpty()) Text("No personal details were returned.", color = Tokens.Colors.TextPrimary.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun WebPersonBiography(person: PersonDetail, state: WebPersonReadState,
                               toggle: (WebPersonReadState) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(person.name.ifBlank { "Unknown person" }, style = displayStyle(48.sp, trackingEm = -0.03f).copy(lineHeight = 1.05.em),
            modifier = Modifier.semantics { heading() })
        Text("Biography" + if (person.biographyInEnglish) " (English)" else "", Modifier.padding(top = 24.dp).semantics { heading() },
            style = displayStyle(20.sp, FontWeight.Bold))
        Text(person.biography.ifBlank { "No biography available." }, Modifier.padding(top = 10.dp).widthIn(max = 820.dp),
            style = bodyStyle(16.sp).copy(lineHeight = 26.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.88f),
            maxLines = if (state.bioExpanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
        if (person.biography.isNotBlank()) Text(if (state.bioExpanded) "Read less" else "Read more",
            Modifier.padding(top = 6.dp).testTag("person-biography-toggle")
                .cvClickable({ toggle(state) }, focusShape = RoundedCornerShape(6.dp), pressedScale = 1f, enabled = state.verified)
                .disabledAlpha(state.verified),
            style = bodyStyle(14.5.sp, FontWeight.Bold), color = Tokens.Colors.Accent)
    }
}

@Composable
private fun WebPersonKnownFor(person: PersonDetail, state: WebPersonReadState, navigate: (WebPersonReadState, MediaRef) -> Unit) {
    if (person.knownFor.isEmpty()) {
        EmptyState("No known-for titles", "No known-for credits were returned.")
        return
    }
    Column {
        WebSectionHeader("Known for")
        LazyRow(contentPadding = PaddingValues(top = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(person.knownFor.distinctBy { it.ref }, key = { it.ref.key }) { title ->
                Column {
                    if (state.verified) PosterCard(title.title.ifBlank { "Unknown title" }, formatDate(title.releaseDate), personPalette,
                        { navigate(state, title.ref) }, Modifier.testTag("person-known-${title.ref.key}"),
                        imageUrl = webImage(title.posterPath, "w342"), voteAverage = title.voteAverage,
                        voteCount = title.voteCount.takeIf { it > 0 } ?: 0)
                    else Column(Modifier.width(180.dp).testTag("person-stale-known-${title.ref.key}")) {
                        PaletteArt(webImage(title.posterPath, "w342"), personPalette, ArtStyle.Poster,
                            Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        Text(title.title.ifBlank { "Unknown title" })
                        formatDate(title.releaseDate)?.let { Text(it) }
                        ScoreRing(title.voteAverage, title.voteCount.takeIf { it > 0 } ?: 0)
                    }
                    if (title.voteCount <= 0) Text("NR", Modifier.semantics { contentDescription = "Not rated" },
                        style = bodyStyle(12.sp, FontWeight.Bold), color = Tokens.Colors.TextPrimary.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun WebPersonCreditsHeading(person: PersonDetail) {
    Column {
        Text(person.creditsTitle.ifBlank { "Credits" }, Modifier.padding(bottom = 12.dp).semantics { heading() }, style = displayStyle(20.sp, FontWeight.Bold))
        if (person.credits.isEmpty()) Text("No credits available.")
    }
}

/** Kit `.crow` inside the `.ctable`: year · media colour dot · title / role; the first/last rows round the table. */
@Composable
private fun WebPersonCreditRow(credit: PersonCredit, first: Boolean, last: Boolean, enabled: Boolean, open: () -> Unit) {
    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)
    val movie = credit.ref.mediaType == MediaType.Movie
    Column(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.04f))) {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
        Row(Modifier.fillMaxWidth().cvClickable(open, enabled = enabled, onClickLabel = "Open ${credit.title.ifBlank { "title" }}", focusShape = shape)
            .testTag("person-credit-${credit.ref.key}").padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(yearOf(credit.date) ?: "—", Modifier.width(48.dp), style = bodyStyle(15.sp, FontWeight.Bold),
                color = Tokens.Colors.TextPrimary.copy(alpha = 0.6f))
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (movie) Tokens.Colors.Accent else Tokens.Colors.AccentAlternates[0])
                .semantics { contentDescription = if (movie) "Movie" else "TV show" })
            Column(Modifier.weight(1f)) {
                Text(credit.title.ifBlank { "Unknown title" }, style = bodyStyle(15.sp, FontWeight.Bold))
                if (credit.role.isNotBlank()) Text(credit.role, style = bodyStyle(13.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.68f))
            }
        }
    }
}

@Composable
private fun WebPersonError(kind: SectionErrorKind, retry: () -> Unit) {
    val message = when (kind) {
        SectionErrorKind.Offline -> "Offline. Check your connection."
        SectionErrorKind.NotAvailable -> "This person is not available."
        SectionErrorKind.AppCredential -> "Catalog configuration is unavailable."
        SectionErrorKind.SessionExpired -> "Session verification is required."
        SectionErrorKind.Generic -> "Could not load this person."
    }
    if (kind.retryable) ErrorState(message, retry, Modifier.testTag("person-catalog-recovery")) else Text(message)
}
