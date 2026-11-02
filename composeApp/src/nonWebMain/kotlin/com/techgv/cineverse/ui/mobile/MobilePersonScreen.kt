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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
internal fun MobilePersonScreen(model: MobilePersonViewModel, navigator: MobileNavigator, onBack: () -> Unit,
                                seedName: String? = null, seedProfilePath: String? = null) {
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
        // The cast card's seed only stands in while the first fetch loads, like Detail's.
        val seed = seedName?.let { PersonHeaderArt(model.id, it, seedProfilePath) }
        PersonContent(state, seed, model.imageBase, scroll, { model.back(token, onBack) },
            { model.retry(token) }, { model.toggleBiography(token) }, { model.open(token, it, navigator) })
    }
}

@Composable
private fun PersonContent(
    state: MobilePersonState,
    seed: PersonHeaderArt?,
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
    // One "header" item call site for seed and Person, so the shared avatar is never remounted when data lands.
    val header = person?.let { PersonHeaderArt(it.id, it.name, it.profilePath) } ?: seed
    val origin = header?.let { rememberMobilePersonOrigin(it.id) }
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
                    else -> {
                        item("header") {
                            if (header != null && origin != null) PersonHeader(header, origin, person, imageBase) else PersonHeaderSkeleton()
                        }
                        if (person == null) {
                            item("loading") { PersonBodySkeleton() }
                            return@LazyColumn
                        }
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

/** What the header's shared photo and name show: the cast card's seed until [PersonDetail] loads, then its own. */
private data class PersonHeaderArt(val id: Int, val name: String, val profilePath: String?)

/** [person] null = seeded while loading: real photo and name, skeleton chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonHeader(art: PersonHeaderArt, origin: String, person: PersonDetail?, imageBase: String) {
    val palette = mobilePalette("person:${art.id}")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(180.dp).background(Brush.radialGradient(listOf(palette.c1.copy(alpha = 0.35f), Color.Transparent))),
            contentAlignment = Alignment.Center) {
            PaletteArt(imageUrl(imageBase, "w185", art.profilePath), palette, ArtStyle.Person,
                mobileSharedAvatar(origin).size(150.dp).clip(CircleShape).testTag("person-avatar"))
        }
        Text(art.name, style = displayStyle(30.sp))
        if (person == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { Box(Modifier.size(84.dp, 28.dp).shimmer(RoundedCornerShape(999.dp))) }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            person.department?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
            person.birthday?.takeIf { it.isNotBlank() }?.let { MetaChip("Born $it") }
            person.deathday?.takeIf { it.isNotBlank() }?.let { MetaChip("Died $it") }
            person.placeOfBirth?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
        }
    }
}

/** Unseeded (deep link) loading header: same footprint as [PersonHeader]. */
@Composable
private fun PersonHeaderSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(150.dp).shimmer(CircleShape)) }
        Box(Modifier.size(200.dp, 30.dp).shimmer())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { Box(Modifier.size(84.dp, 28.dp).shimmer(RoundedCornerShape(999.dp))) }
        }
    }
}

/** Biography, Known for and Credits placeholders, so loading fills the page instead of a lone card. */
@Composable
private fun PersonBodySkeleton() {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Loading person…" },
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SkeletonLines(4, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.padding(horizontal = 20.dp).size(120.dp, 18.dp).shimmer())
            Row(Modifier.fillMaxWidth().clipToBounds().wrapContentWidth(Alignment.Start, unbounded = true).padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(4) { PosterCardSkeleton() } }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(100.dp, 18.dp).shimmer())
            repeat(5) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp, 12.dp).shimmer())
                    Box(Modifier.size(44.dp, 66.dp).shimmer(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(0.7f).height(12.dp).shimmer())
                        Box(Modifier.fillMaxWidth(0.4f).height(10.dp).shimmer())
                    }
                }
            }
        }
    }
}
