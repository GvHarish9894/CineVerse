package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
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
    LaunchedEffect(scroll, person != null, state.verified) {
        if (person == null || !state.verified || restored) return@LaunchedEffect
        // Wait for the real list, not the single loading/error row, before consuming the saved target.
        snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it == person.credits.size + 5 }
        scroll.scrollToItem(entry.index.coerceAtMost(person.credits.size + 4), entry.offset)
        restored = true
    }
    LaunchedEffect(scroll, restored) {
        if (restored) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) -> currentSave(currentState, index, offset) }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val desktop = this.maxWidth > 860.dp
        val gutter = if (this.maxWidth <= 640.dp) 16.dp else 32.dp
        LazyColumn(state = scroll, modifier = Modifier.widthIn(max = 1320.dp).fillMaxSize().testTag("web-person")
            .semantics { contentDescription = "Person content: " + if (state.verified) "verified instance" else "unverified" },
            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item("status") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("← Back", { back(state) }, variant = ButtonVariant.Ghost)
                    if (storage != null) WebStorageNotice(storage, retryReads, Modifier.testTag("person-storage-recovery"))
                    if (state.reader == WebPersonReader.Failed) ErrorState(
                        "Saved preferences could not be read. Person content is unverified; saved data was not changed.",
                        retryReads, Modifier.testTag("person-settings-recovery"))
                    if (person != null && !state.verified) Text("Previously loaded person · stale / unverified until replacement content completes.")
                    when (val section = state.section) {
                        SectionState.Loading -> Text(if (state.reader == WebPersonReader.Unknown) "Verifying saved preferences…" else "Loading person…")
                        SectionState.Empty -> EmptyState("Person unavailable", "No person was returned.")
                        is SectionState.Error -> WebPersonError(section.kind) { retryPerson(state) }
                        is SectionState.Content -> Unit
                    }
                }
            }
            if (person != null) {
                item("profile") {
                    if (desktop) Row(Modifier.fillMaxWidth().testTag("person-desktop"), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        WebPersonInfo(person, Modifier.width(300.dp))
                        WebPersonBiography(person, state, toggleBiography, Modifier.weight(1f))
                    } else Column(Modifier.fillMaxWidth().testTag("person-narrow"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        WebPersonInfo(person, Modifier.widthIn(max = 300.dp))
                        WebPersonBiography(person, state, toggleBiography, Modifier.fillMaxWidth())
                    }
                }
                item("known-for") {
                    if (person.knownFor.isEmpty()) EmptyState("No known-for titles", "No known-for credits were returned.")
                    else ScrollRow("Known for") {
                        items(person.knownFor.distinctBy { it.ref }, key = { it.ref.key }) { title ->
                            Column {
                                if (state.verified) PosterCard(title.title.ifBlank { "Unknown title" }, title.releaseDate, personPalette,
                                    { navigate(state, title.ref) }, Modifier.testTag("person-known-${title.ref.key}"),
                                    imageUrl = webImage(title.posterPath, "w342"), voteAverage = title.voteAverage,
                                    voteCount = title.voteCount.takeIf { it > 0 } ?: 0)
                                else Column(Modifier.width(180.dp).testTag("person-stale-known-${title.ref.key}")) {
                                    PaletteArt(webImage(title.posterPath, "w342"), personPalette, ArtStyle.Poster,
                                        Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                                    Text(title.title.ifBlank { "Unknown title" })
                                    title.releaseDate?.let { Text(it) }
                                    ScoreRing(title.voteAverage, title.voteCount.takeIf { it > 0 } ?: 0)
                                }
                                if (title.voteCount <= 0) Text("NR", Modifier.semantics { contentDescription = "Not rated" })
                            }
                        }
                    }
                }
                item("credits-heading") {
                    SectionTitle(person.creditsTitle.ifBlank { "Credits" })
                    if (person.credits.isEmpty()) Text("No credits available.")
                }
                items(person.credits, key = { it.ref.key }) { credit ->
                    Row(Modifier.fillMaxWidth().cvClickable({ navigate(state, credit.ref) }, enabled = state.verified,
                        onClickLabel = "Open ${credit.title.ifBlank { "title" }}")
                        .testTag("person-credit-${credit.ref.key}").padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(credit.date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: "Unknown year", Modifier.width(100.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(credit.title.ifBlank { "Unknown title" }, style = MaterialTheme.typography.titleMedium)
                            Text(if (credit.ref.mediaType == MediaType.Movie) "Movie" else "TV show", color = Tokens.Colors.Accent)
                            Text(credit.role.ifBlank { "Role unknown" })
                        }
                    }
                }
                item("attribution") { Text(TMDB_ATTRIBUTION, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private val personPalette = ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary)

@Composable
private fun WebPersonInfo(person: PersonDetail, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PaletteArt(webImage(person.profilePath, "w342"), personPalette, ArtStyle.Person,
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).semantics { contentDescription = "Portrait of ${person.name.ifBlank { "unknown person" }}" })
        Panel(title = "Personal info") {
            listOf("Known for" to person.department, "Unique credits" to person.creditCount.toString(),
                "Birthday" to person.birthday, "Deathday" to person.deathday, "Place of birth" to person.placeOfBirth).forEach { (label, value) ->
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(value?.takeIf { it.isNotBlank() } ?: "Unknown", Modifier.padding(bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun WebPersonBiography(person: PersonDetail, state: WebPersonReadState,
                               toggle: (WebPersonReadState) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(person.name.ifBlank { "Unknown person" }, style = displayStyle(48.sp), modifier = Modifier.semantics { heading() })
        SectionTitle("Biography" + if (person.biographyInEnglish) " (English)" else "")
        Text(person.biography.ifBlank { "No biography available." }, maxLines = if (state.bioExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis)
        if (person.biography.isNotBlank()) PillButton(if (state.bioExpanded) "Read less" else "Read more",
            { toggle(state) }, Modifier.testTag("person-biography-toggle"), variant = ButtonVariant.Ghost, enabled = state.verified)
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
