package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.PersonDetail
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.theme.displayStyle
import kotlinx.coroutines.flow.distinctUntilChanged

/** Composition renders only; the route host owns activation and the entry store owns final close. */
@Composable
internal fun MobilePersonScreen(model: MobilePersonViewModel, navigator: MobileNavigator, onBack: () -> Unit) {
    val snapshot by model.state.collectAsState()
    val state = model.visibleState(snapshot)
    DisposableEffect(model) { onDispose { model.setActive(false) } }
    key(model, state.context) {
        val token = state.context
        val position = remember { state.index to state.offset }
        val scroll = remember { LazyListState() }
        var restorationBaseline by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var positionChanged by remember { mutableStateOf(false) }
        val restored = restorationBaseline != null
        val ready = !state.invalid && !state.settingsFailed &&
            (state.section as? SectionState.Content)?.value?.id == model.id
        val persist by rememberUpdatedState(restored && ready)
        LaunchedEffect(scroll, ready) {
            if (!ready || restored) return@LaunchedEffect
            // The real provider is remeasured by scrollToItem; the header need not be visible.
            scroll.scrollToItem(position.first, position.second)
            restorationBaseline = scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset
        }
        LaunchedEffect(scroll, restored, ready) {
            if (persist) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                .distinctUntilChanged().collect { current ->
                    // Restoration's realized position may be a clamp, not a new saved target.
                    if (persist && (positionChanged || current != restorationBaseline)) {
                        positionChanged = true
                        model.saveScroll(token, current.first, current.second)
                    }
                }
        }
        DisposableEffect(scroll) {
            onDispose {
                val current = scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset
                if (persist && (positionChanged || current != restorationBaseline))
                    model.saveScroll(token, current.first, current.second)
            }
        }
        PersonContent(state, model.imageBase, scroll, { model.back(token, onBack) },
            { model.retry(token) }, { model.toggleBiography(token) }, { model.open(token, it, navigator) })
    }
}

@Composable
private fun PersonContent(
    state: MobilePersonState,
    imageBase: String,
    scroll: LazyListState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onBiography: () -> Unit,
    onOpen: (MediaRef) -> Unit,
) {
    val person = (state.section as? SectionState.Content)?.value
    val palette = mobilePalette("person:${person?.id ?: "unknown"}")
    val solid = rememberTopBarSolid(scroll, 200.dp)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).imePadding()) {
        AmbientGlow(palette)
        Column {
            GlassTopBar(solid, title = person?.name?.takeIf { solid },
                navigation = { RoundIconButton(CvIcons.ChevronLeft, "Back", onBack) })
            LazyColumn(Modifier.fillMaxSize().testTag("mobile-person"), state = scroll,
                contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when {
                    state.invalid -> item("invalid") { Text("Invalid person or saved state. Go back to choose a person.", Modifier.padding(20.dp)) }
                    state.settingsFailed -> item("settings-error") {
                        ErrorState("Saved preferences unavailable. Retry reads.", onRetry, Modifier.padding(20.dp))
                    }
                    state.section is SectionState.Error -> item("catalog-error") {
                        Column(Modifier.padding(20.dp)) { SectionFailure(state.section.kind, onRetry) }
                    }
                    person == null && state.section == SectionState.Empty -> item("empty") {
                        Text("Person unavailable. Go back to choose a person.", Modifier.padding(20.dp))
                    }
                    person == null -> item("loading") {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PosterCardSkeleton()
                            Text("Loading person…")
                        }
                    }
                    else -> {
                        item("header") { PersonHeader(person, imageBase) }
                        item("biography") {
                            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (person.biography.isBlank()) Text("No biography available.") else {
                                    var overflows by remember(person.biography) { mutableStateOf(false) }
                                    Text(person.biography, maxLines = if (state.expanded) Int.MAX_VALUE else 4,
                                        overflow = TextOverflow.Ellipsis, onTextLayout = { if (!state.expanded) overflows = it.hasVisualOverflow })
                                    if (person.biographyInEnglish) Text("(English)")
                                    if (overflows || state.expanded) PillButton(if (state.expanded) "Read less" else "Read more",
                                        onBiography, variant = ButtonVariant.Ghost, small = true)
                                }
                            }
                        }
                        val known = person.knownFor.filter { it.ref.id > 0 }
                        if (known.isNotEmpty()) item("known-for") {
                            ScrollRow("Known for") {
                                items(known, key = { it.ref.key }) { title ->
                                    PosterCard(title.title, titleMeta(title), mobilePalette(title.ref.key), { onOpen(title.ref) },
                                        modifier = Modifier.testTag("person-known-${title.ref.key}"),
                                        imageUrl = imageUrl(imageBase, "w342", title.posterPath), voteAverage = title.voteAverage, voteCount = title.voteCount)
                                }
                            }
                        }
                        item("credits-title") { SectionTitle(if (person.department.isNullOrBlank()) "Credits" else person.creditsTitle, Modifier.padding(horizontal = 20.dp)) }
                        val credits = person.credits.filter { it.ref.id > 0 }
                        if (credits.isEmpty()) item("no-credits") { Text("No credits available.", Modifier.padding(horizontal = 20.dp)) }
                        items(credits, key = { "credit:${it.ref.key}" }) { credit ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("person-credit-${credit.ref.key}")
                                .cvClickable({ onOpen(credit.ref) }), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                credit.date?.take(4)?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.width(40.dp)) }
                                PaletteArt(imageUrl(imageBase, "w185", credit.posterPath), mobilePalette(credit.ref.key), ArtStyle.Poster,
                                    Modifier.size(44.dp, 66.dp).clip(RoundedCornerShape(8.dp)))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(credit.title)
                                    if (credit.role.isNotBlank()) Text(credit.role)
                                }
                                Text("›")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonHeader(person: PersonDetail, imageBase: String) {
    val palette = mobilePalette("person:${person.id}")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(180.dp).background(Brush.radialGradient(listOf(palette.c1.copy(alpha = 0.35f), Color.Transparent))),
            contentAlignment = Alignment.Center) {
            PaletteArt(imageUrl(imageBase, "w185", person.profilePath), palette, ArtStyle.Person,
                Modifier.size(150.dp).clip(CircleShape))
        }
        Text(person.name, style = displayStyle(30.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            person.department?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
            person.birthday?.takeIf { it.isNotBlank() }?.let { MetaChip("Born $it") }
            person.deathday?.takeIf { it.isNotBlank() }?.let { MetaChip("Died $it") }
            person.placeOfBirth?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
        }
    }
}
