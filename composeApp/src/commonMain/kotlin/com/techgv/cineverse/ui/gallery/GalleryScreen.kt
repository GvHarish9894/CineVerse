package com.techgv.cineverse.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.LocalShellStyle
import com.techgv.cineverse.ui.adaptive.LocalWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.ActionTile
import com.techgv.cineverse.ui.components.AmbientGlow
import com.techgv.cineverse.ui.components.ArtPalette
import com.techgv.cineverse.ui.components.ArtStyle
import com.techgv.cineverse.ui.components.ButtonVariant
import com.techgv.cineverse.ui.components.Chip
import com.techgv.cineverse.ui.components.CineVerseTabs
import com.techgv.cineverse.ui.components.CvBottomSheet
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.CvIcons
import com.techgv.cineverse.ui.components.EmptyState
import com.techgv.cineverse.ui.components.EpisodeCard
import com.techgv.cineverse.ui.components.ErrorState
import com.techgv.cineverse.ui.components.FactTile
import com.techgv.cineverse.ui.components.FloatingTabBar
import com.techgv.cineverse.ui.components.Footer
import com.techgv.cineverse.ui.components.FooterLink
import com.techgv.cineverse.ui.components.GlassTopBar
import com.techgv.cineverse.ui.components.LandscapeCard
import com.techgv.cineverse.ui.components.LocalToasts
import com.techgv.cineverse.ui.components.LogoMark
import com.techgv.cineverse.ui.components.MetaChip
import com.techgv.cineverse.ui.components.OverlayHeading
import com.techgv.cineverse.ui.components.PaletteArt
import com.techgv.cineverse.ui.components.Panel
import com.techgv.cineverse.ui.components.PersonCard
import com.techgv.cineverse.ui.components.PersonCircle
import com.techgv.cineverse.ui.components.PersonCircleSkeleton
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.PosterCardSkeleton
import com.techgv.cineverse.ui.components.QuickAction
import com.techgv.cineverse.ui.components.ReviewCard
import com.techgv.cineverse.ui.components.RoundIconButton
import com.techgv.cineverse.ui.components.ScoreRing
import com.techgv.cineverse.ui.components.ScoreRingSize
import com.techgv.cineverse.ui.components.ScrollRow
import com.techgv.cineverse.ui.components.SectionTitle
import com.techgv.cineverse.ui.components.SegmentedControl
import com.techgv.cineverse.ui.components.SettingRow
import com.techgv.cineverse.ui.components.SettingTrailing
import com.techgv.cineverse.ui.components.SettingsGroup
import com.techgv.cineverse.ui.components.SkeletonLines
import com.techgv.cineverse.ui.components.ToastMessage
import com.techgv.cineverse.ui.components.Top10Card
import com.techgv.cineverse.ui.components.VideoCard
import com.techgv.cineverse.ui.components.oneDecimal
import com.techgv.cineverse.ui.components.rememberTopBarSolid
import com.techgv.cineverse.ui.design.Scrims.mobileHeroScrim
import com.techgv.cineverse.ui.design.Scrims.webHeroScrim
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticStrength
import com.techgv.cineverse.ui.resources.Res
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.LocalReduceMotion
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle

const val GALLERY_ROUTE = "gallery"

/** Credential-free TMDB image host; proves network images load in the card primitives (and is the wasm-scan control literal). */
const val GALLERY_NETWORK_POSTER = "https://image.tmdb.org/t/p/w342/qJ2tW6WMUDux911r6m7haRef0WH.jpg"

private sealed interface SampleLoad {
    data object Loading : SampleLoad
    data class Loaded(val samples: GallerySamples) : SampleLoad
    data class Failed(val message: String) : SampleLoad
}

/**
 * Every primitive in its loading / empty / error / content states, with sample data from design/sample-tmdb.json.
 * The Feel settings at the bottom drive the live [AppearanceSettings] (reduce motion, glow, haptics, strength).
 */
@Composable
fun GalleryScreen(settings: AppearanceSettings, onSettingsChange: (AppearanceSettings) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val load by produceState<SampleLoad>(SampleLoad.Loading, reload) {
        value = SampleLoad.Loading
        value = runCatching { SampleLoad.Loaded(parseGallerySamples(Res.readBytes("files/sample-tmdb.json").decodeToString())) }
            .getOrElse { SampleLoad.Failed(it.message ?: it.toString()) }
        // Greppable per-platform proof (logcat / simulator console / browser console) that resources + parsing work.
        println("CineVerseGallery samples=" + ((value as? SampleLoad.Loaded)?.samples?.let { "loaded movies=${it.movies.size} tv=${it.tv.size} people=${it.people.size}" } ?: "error"))
    }
    val listState = rememberLazyListState()
    val solid = rememberTopBarSolid(listState)
    val samples = (load as? SampleLoad.Loaded)?.samples
    var featured by remember { mutableIntStateOf(0) }
    val glowPalette = samples?.movies?.getOrNull(featured)?.palette ?: ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary)

    Box(Modifier.fillMaxSize().background(Tokens.Colors.Background)) {
        AmbientGlow(glowPalette)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Spacer(Modifier.height(112.dp)) }
            item { GalleryHeader() }
            when (val state = load) {
                SampleLoad.Loading -> item { LoadingRows() }
                is SampleLoad.Failed -> item { Pad { ErrorState("Couldn't load sample data: ${state.message}", onRetry = { reload++ }) } }
                is SampleLoad.Loaded -> content(state.samples, featured, { featured = it }, settings, onSettingsChange)
            }
            item { Footer(browse = listOf(FooterLink("Movies") {}, FooterLink("TV shows") {}, FooterLink("People") {}), you = listOf(FooterLink("My library") {}, FooterLink("Settings") {})) }
            item { Spacer(Modifier.height(130.dp)) }
        }
        GlassTopBar(
            solid = solid,
            title = "Design system",
            navigation = { LogoMark(size = 34.dp) },
            actions = { RoundIconButton(CvIcons.Search, "Search", {}) },
        )
    }
}

private fun LazyListScope.content(
    samples: GallerySamples,
    featured: Int,
    onFeatured: (Int) -> Unit,
    settings: AppearanceSettings,
    onSettingsChange: (AppearanceSettings) -> Unit,
) {
    item { Swatches() }
    item { TypeScale() }
    item { Buttons() }
    item { ChipsAndSegments(samples, featured, onFeatured) }
    item { ScoreRings() }
    item { PosterStates(samples) }
    item {
        ScrollRow("Top 10 movies", itemSpacing = 6.dp) {
            itemsIndexed(samples.movies.take(10)) { i, t -> Top10Card(i + 1, t.name, t.palette, {}) }
        }
    }
    item {
        ScrollRow("Popular on TV") {
            items(samples.tv) { t -> LandscapeCard(t.name, t.genres.firstOrNull(), t.palette, {}) }
        }
    }
    item { PeopleRows(samples) }
    item {
        ScrollRow("Videos") {
            items(samples.movies.take(4)) { t -> VideoCard("${t.name} — Official Trailer", "Trailer  ·  2:${10 + t.id % 49}  ·  YouTube", t.palette, {}) }
        }
    }
    item { Episodes(samples.tv.first()) }
    item { Reviews() }
    item { Facts(samples.movies.first()) }
    item { Settings(settings, onSettingsChange) }
    item { OverlaysDemo(samples.movies.first()) }
    item { BarsDemo() }
    item { ScrimDemo(samples.movies[1]) }
}

/** A gallery section padded by the shell's gutter (mobile 20, web 32). */
@Composable
private fun Pad(content: @Composable ColumnScope.() -> Unit) {
    val gutter = if (LocalShellStyle.current == ShellStyle.Web) Tokens.Spacing.WebGutter else Tokens.Spacing.MobileGutter
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 14.dp), content = content)
}

@Composable
private fun GalleryHeader() = Pad {
    Text("CineVerse design system", style = displayStyle(if (LocalShellStyle.current == ShellStyle.Web) 48.sp else 34.sp, trackingEm = -0.03f))
    Text(
        "Shell ${LocalShellStyle.current} · window ${LocalWindowClass.current} · reduce motion ${if (LocalReduceMotion.current) "on" else "off"}",
        style = bodyStyle(14.sp),
        color = Tokens.Colors.TextPrimary.copy(alpha = 0.68f),
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LoadingRows() = Column {
    ScrollRow("Loading") { items(4) { PosterCardSkeleton() } }
    ScrollRow("Loading people") { items(4) { PersonCircleSkeleton() } }
}

@Composable
private fun Swatches() = Pad {
    SectionTitle("Colour")
    val colors = listOf(
        "background" to Tokens.Colors.Background, "surface" to Tokens.Colors.Surface, "surfaceRaised" to Tokens.Colors.SurfaceRaised,
        "accent" to Tokens.Colors.Accent, "secondary" to Tokens.Colors.Secondary, "gold" to Tokens.Colors.Gold,
        "scoreHigh" to Tokens.Colors.ScoreHigh, "scoreMid" to Tokens.Colors.ScoreMid, "scoreLow" to Tokens.Colors.ScoreLow,
        "scoreNone" to Tokens.Colors.ScoreNone, "danger" to Tokens.Colors.Danger, "tile" to Tokens.Colors.Tile,
    )
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        colors.forEach { (name, color) ->
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).padding(1.dp).clip(CircleShape).background(color))
                Text(name, style = bodyStyle(11.sp), color = Tokens.Colors.TextSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TypeScale() = Pad {
    SectionTitle("Type")
    val web = LocalShellStyle.current == ShellStyle.Web
    Text("Neon Tide", style = displayStyle(if (web) Tokens.Typography.ScaleWeb.Hero else Tokens.Typography.ScaleMobile.HeroTitle, trackingEm = -0.03f), modifier = Modifier.padding(top = 8.dp))
    Text("Bricolage Grotesque 800 · display", style = displayStyle(20.sp, FontWeight.Bold))
    Text("Inter 400 body — when a coastal megacity floods overnight, a salvage diver discovers the water is carrying signals.", style = bodyStyle(if (web) Tokens.Typography.ScaleWeb.Body else Tokens.Typography.ScaleMobile.Body))
    Text("Inter 600 label · 2026 · 2h 8m · ★ 8.1", style = bodyStyle(Tokens.Typography.ScaleMobile.Label, FontWeight.SemiBold), color = Tokens.Colors.TextSecondary)
}

@Composable
private fun Buttons() = Pad {
    SectionTitle("Buttons and tiles")
    var saved by remember { mutableStateOf(false) }
    var rated by remember { mutableStateOf<Double?>(null) }
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Trailer", {}, icon = CvIcons.Play)
        PillButton(if (saved) "Saved" else "Watchlist", { saved = !saved }, variant = ButtonVariant.Glass, icon = if (saved) CvIcons.Check else CvIcons.Plus)
        PillButton("Ghost", {}, variant = ButtonVariant.Ghost)
        PillButton("Sign in with TMDB", {}, variant = ButtonVariant.White)
        PillButton("Small", {}, small = true)
        RoundIconButton(CvIcons.Info, "More info", {}, diameter = 46.dp, container = Color.White.copy(alpha = 0.14f))
        RoundIconButton(CvIcons.Share, "Share", {})
        RoundIconButton(CvIcons.Heart, "Favourite", {}, container = Tokens.Colors.Accent, content = Tokens.Colors.OnAccent)
    }
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionTile(
            if (saved) "On watchlist" else "Watchlist", if (saved) CvIcons.Check else CvIcons.Plus, { saved = !saved }, Modifier.weight(1f),
            container = if (saved) Tokens.Colors.Accent else Color.White.copy(alpha = 0.08f), content = if (saved) Tokens.Colors.OnAccent else Tokens.Colors.TextPrimary,
        )
        ActionTile(
            rated?.let { "Rated ${oneDecimal(it)}" } ?: "Rate", if (rated != null) CvIcons.StarFilled else CvIcons.Star, { rated = if (rated == null) 8.0 else null }, Modifier.weight(1f),
            container = if (rated != null) Tokens.Colors.Gold else Color.White.copy(alpha = 0.08f), content = if (rated != null) Tokens.Colors.OnGold else Tokens.Colors.TextPrimary,
        )
        ActionTile("Trailer", CvIcons.Play, {}, Modifier.weight(1f))
    }
}

@Composable
private fun ChipsAndSegments(samples: GallerySamples, featured: Int, onFeatured: (Int) -> Unit) = Pad {
    SectionTitle("Chips and segments")
    var list by remember { mutableIntStateOf(0) }
    var trend by remember { mutableIntStateOf(0) }
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Popular", "Now playing", "Upcoming", "Top rated").forEachIndexed { i, label -> Chip(label, list == i, { list = i }) }
    }
    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        samples.movies.first().genres.forEach { MetaChip(it) }
        MetaChip("UA 13+")
    }
    SegmentedControl(listOf("Today", "This week"), trend, { trend = it }, Modifier.padding(top = 14.dp).width(176.dp), compact = true)
    Text("Featured title (drives the ambient glow)", style = bodyStyle(13.sp), color = Tokens.Colors.TextSecondary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    SegmentedControl(samples.movies.take(3).map { it.name }, featured, onFeatured, Modifier.fillMaxWidth())
}

@Composable
private fun ScoreRings() = Pad {
    SectionTitle("Score ring")
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(8.1, 120, size = ScoreRingSize.Mini)
        ScoreRing(5.5, 40, size = ScoreRingSize.Mobile)
        ScoreRing(2.3, 12, size = ScoreRingSize.Web)
        ScoreRing(10.0, 3, size = ScoreRingSize.Large)
        ScoreRing(7.0, 0)
        Text("vote_count 0 → no ring", style = bodyStyle(12.sp), color = Tokens.Colors.TextTertiary)
    }
}

@Composable
private fun PosterStates(samples: GallerySamples) {
    val toasts = LocalToasts.current
    var watch by remember { mutableStateOf(setOf<Int>()) }
    var failed by remember { mutableStateOf(true) }
    ScrollRow("Trending") {
        item {
            PosterCard("Network image", "Coil · image.tmdb.org", ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary), {}, imageUrl = GALLERY_NETWORK_POSTER, voteAverage = 7.4, voteCount = 10)
        }
        items(samples.movies.take(8) + samples.tv.take(2)) { t ->
            val on = t.id in watch
            PosterCard(
                title = t.name,
                subtitle = if (t.voteAverage != null) "${t.year}  ·  ★ ${oneDecimal(t.voteAverage)}" else "Coming ${t.date}",
                palette = t.palette,
                onClick = {},
                voteAverage = t.voteAverage ?: 0.0,
                voteCount = t.voteCount,
                tag = if (t.isTv) "TV" else null,
                badge = if (t.voteAverage == null) t.date else null,
                quickAction = QuickAction(on, if (on) "Remove from watchlist" else "Add to watchlist") {
                    watch = if (on) watch - t.id else watch + t.id
                    toasts.show(ToastMessage(if (on) "Removed from Watchlist" else "Added to Watchlist", "${t.name} · saved on this device", if (on) CvIcons.Close else CvIcons.Check))
                },
            )
        }
    }
    ScrollRow("Loading state") { items(5) { PosterCardSkeleton() } }
    Pad {
        SectionTitle("Empty and error states")
        Spacer(Modifier.height(8.dp))
        if (failed) ErrorState("Couldn't load Now playing.", onRetry = { failed = false }) else Text("Loaded after retry.", style = bodyStyle(14.sp))
        EmptyState("Nothing saved yet", "Tap + on any title to keep it here. Your list syncs with TMDB when you sign in.") {
            PillButton("Explore movies", {})
        }
    }
}

@Composable
private fun PeopleRows(samples: GallerySamples) {
    ScrollRow("Popular people", itemSpacing = 14.dp) {
        items(samples.people.take(8)) { p -> PersonCircle(p.name, p.department, p.palette, {}) }
    }
    ScrollRow("People (web card)") {
        items(samples.people.drop(8).take(6)) { p -> PersonCard(p.name, p.knownFor.firstOrNull() ?: p.department, p.palette, {}) }
    }
}

@Composable
private fun Episodes(show: PreviewTitle) = Pad {
    SectionTitle("Episodes")
    Spacer(Modifier.height(10.dp))
    listOf("Low Tide", "The Long Way Round").forEachIndexed { i, name ->
        EpisodeCard(
            "S1 · E${i + 1}", name, "Apr ${12 + 7 * i}, 2024 · 4${6 + i}m · ★ 8.${2 - i}",
            "${show.name}: the signal changes when the tide turns, and nobody on the station agrees what it means.",
            show.palette, {}, Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun Reviews() = Pad {
    SectionTitle("Reviews")
    Spacer(Modifier.height(10.dp))
    ReviewCard("Maya Fernandes", "Mar 20, 2026", 8.0, "Gorgeous, strange and warm. The flooded-city sequences are some of the best large-scale filmmaking this year, and the score does a lot of quiet work.")
    Spacer(Modifier.height(10.dp))
    SkeletonLines(3, Modifier.padding(16.dp))
}

@Composable
private fun Facts(title: PreviewTitle) = Pad {
    SectionTitle("Facts")
    val facts = listOf("Status" to (title.status ?: "—"), "Release" to (title.date ?: "—"), "Certification" to (title.certification ?: "—"), "Genres" to title.genres.joinToString())
    facts.chunked(2).forEach { pair ->
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { (label, value) -> FactTile(label, value, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Settings(settings: AppearanceSettings, onChange: (AppearanceSettings) -> Unit) = Pad {
    SectionTitle("Feel (live)")
    Spacer(Modifier.height(10.dp))
    SettingsGroup {
        SettingRow("Haptic feedback", "Taps, toggles, swipes and success", CvIcons.Vibrate, Tokens.Colors.Accent, SettingTrailing.Toggle(settings.haptics) { onChange(settings.copy(haptics = it)) })
        SettingRow(
            "Haptic strength", "Tap to cycle · 6, 10 or 16 ms", CvIcons.Vibrate, Tokens.Colors.Accent,
            SettingTrailing.Value(settings.hapticStrength.name) {
                onChange(settings.copy(hapticStrength = HapticStrength.entries[(settings.hapticStrength.ordinal + 1) % HapticStrength.entries.size]))
            },
            showDivider = true,
        )
        SettingRow("Ambient glow", "Screens pick up the poster’s colours", CvIcons.Glow, Color(0xFFFFB020), SettingTrailing.Toggle(settings.ambientGlow) { onChange(settings.copy(ambientGlow = it)) }, showDivider = true)
        SettingRow("Reduce motion", "No zooms, slides or fades", CvIcons.Motion, Color(0xFF22D3EE), SettingTrailing.Toggle(settings.reduceMotion) { onChange(settings.copy(reduceMotion = it)) }, showDivider = true)
    }
}

@Composable
private fun OverlaysDemo(title: PreviewTitle) = Pad {
    SectionTitle("Sheets, dialogs, toasts")
    val toasts = LocalToasts.current
    var sheet by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    val sheetTrigger = remember { FocusRequester() }
    val dialogTrigger = remember { FocusRequester() }
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Open sheet", { sheet = true }, Modifier.focusRequester(sheetTrigger), variant = ButtonVariant.Ghost)
        PillButton("Open dialog", { dialog = true }, Modifier.focusRequester(dialogTrigger), variant = ButtonVariant.Ghost)
        PillButton("Show toast", { toasts.show(ToastMessage("Rated 8.0 / 10", "${title.name} · saved on this device", CvIcons.StarFilled)) }, variant = ButtonVariant.Ghost)
    }
    if (sheet) {
        CvBottomSheet(onDismissRequest = { sheet = false }, restoreFocus = sheetTrigger) {
            OverlayHeading("Your TMDB account", "Sign in to sync")
            Text("Watchlist, favourites and ratings follow you to every device.", style = bodyStyle(14.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.78f), modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Not now", { sheet = false }, Modifier.weight(1f), variant = ButtonVariant.Ghost)
                PillButton("Continue with TMDB", { sheet = false }, Modifier.weight(1f))
            }
        }
    }
    if (dialog) {
        CvDialog(onDismissRequest = { dialog = false }, title = "Rate ${title.name}", kicker = "Your rating", restoreFocus = dialogTrigger) {
            Text("Esc, the close button or a tap outside dismiss; focus stays inside while open.", style = bodyStyle(15.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.8f), modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Remove", { dialog = false }, variant = ButtonVariant.Ghost)
                PillButton("Submit", { dialog = false })
            }
        }
    }
}

@Composable
private fun BarsDemo() = Pad {
    SectionTitle("Top bar and tab bar")
    var tab by remember { mutableIntStateOf(0) }
    listOf(false, true).forEach { solid ->
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(Tokens.Colors.Surface).consumeWindowInsets(WindowInsets.systemBars)) {
            GlassTopBar(solid = solid, title = if (solid) "Solid (scrolled)" else null, navigation = { RoundIconButton(CvIcons.ChevronLeft, "Back", {}) }, actions = { RoundIconButton(CvIcons.Share, "Share", {}) })
        }
    }
    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(Tokens.Colors.Surface).consumeWindowInsets(WindowInsets.systemBars)) {
        FloatingTabBar(CineVerseTabs, tab, { tab = it })
    }
}

@Composable
private fun ScrimDemo(title: PreviewTitle) = Pad {
    SectionTitle("Hero scrims and panel")
    val web = LocalShellStyle.current == ShellStyle.Web
    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
        PaletteArt(null, title.palette, ArtStyle.Backdrop, Modifier.fillMaxSize().then(if (web) Modifier.webHeroScrim() else Modifier.mobileHeroScrim()))
        Text(title.name, style = displayStyle(32.sp, trackingEm = -0.03f), modifier = Modifier.align(Alignment.BottomStart).padding(20.dp))
    }
    Panel(Modifier.padding(top = 14.dp).fillMaxWidth(), title = "Personal info") {
        FactTile("Known for", "Acting")
        Spacer(Modifier.height(12.dp))
        FactTile("Place of birth", "Mumbai, India")
    }
}
