package com.techgv.cineverse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.theme.LocalAmbientGlowEnabled
import com.techgv.cineverse.ui.theme.bodyStyle

/** Frosted fill of the solid top bar (`.bar.solid`: rgba(12,10,24,.8); Compose has no backdrop blur). */
private val BarSolid = Color(12, 10, 24).copy(alpha = 0.8f)

/** True once [state] scrolled past [threshold] (kit: Home 420, Detail 250, Person 200, others 60). */
@Composable
fun rememberTopBarSolid(state: LazyListState, threshold: Dp = 60.dp): Boolean {
    val px = with(LocalDensity.current) { threshold.toPx() }
    val solid by remember(state, px) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > px } }
    return solid
}

@Composable
fun rememberTopBarSolid(state: ScrollState, threshold: Dp = 60.dp): Boolean {
    val px = with(LocalDensity.current) { threshold.toPx() }
    val solid by remember(state, px) { derivedStateOf { state.value > px } }
    return solid
}

/**
 * Mobile top bar (`.bar`): status inset + 52dp. Transparent over a top scrim, then frosted with a hairline once
 * [solid]; the centred [title] fades/rises in when solid. [navigation] / [actions] hold [RoundIconButton]s.
 */
@Composable
fun GlassTopBar(
    solid: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    navigation: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val fill by animateColorAsState(if (solid) BarSolid else BarSolid.copy(alpha = 0f), tween(300), label = "barFill")
    val solidness by animateFloatAsState(if (solid) 1f else 0f, tween(250), label = "barTitle")
    Box(modifier.fillMaxWidth()) {
        // Transparent state: .bar::before gradient, 30dp taller than the bar.
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = 1f - solidness }
                .background(Brush.verticalGradient(listOf(Tokens.Colors.Background.copy(alpha = 0.72f), Tokens.Colors.Background.copy(alpha = 0f)))),
        )
        Column(Modifier.fillMaxWidth().background(fill)) {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 52.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    navigation()
                    Box(Modifier.weight(1f))
                    actions()
                }
                if (title != null) {
                    Text(
                        title,
                        style = bodyStyle(16.sp, FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 54.dp).graphicsLayer {
                            alpha = solidness
                            translationY = (1f - solidness) * 6.dp.toPx()
                        }.semantics { heading() },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f * solidness)))
        }
    }
}

@Immutable
data class TabItem(val label: String, val icon: ImageVector)

/** The mobile kit's five tabs. */
val CineVerseTabs = listOf(
    TabItem("Home", CvIcons.Home),
    TabItem("Explore", CvIcons.Explore),
    TabItem("Search", CvIcons.Search),
    TabItem("Library", CvIcons.Library),
    TabItem("Profile", CvIcons.User),
)

/**
 * Floating glass tab bar (`.tabbar`): 70dp, radius 26, 12dp side / 16dp bottom inset above the navigation bar,
 * over a 140dp bottom scrim (`.tabscrim`). Active tab: icon in a 48×30 accent-tinted pill with an accent glow.
 * Place it at the bottom of a Box; re-selecting the active tab is reported too (pop to root / scroll to top).
 */
@Composable
fun BoxScope.FloatingTabBar(tabs: List<TabItem>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp)
            .background(Brush.verticalGradient(0.0f to Tokens.Colors.Background.copy(alpha = 0f), 0.8f to Tokens.Colors.Background.copy(alpha = 0.96f))),
    )
    val shape = RoundedCornerShape(Tokens.Radius.TabBar)
    Row(
        modifier.align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
            .fillMaxWidth()
            .heightIn(min = Tokens.Size.MobileTabBar)
            .dropShadow(shape, Shadow(50.dp, Color.Black.copy(alpha = 0.55f), offset = DpOffset(0.dp, 22.dp)))
            .clip(shape)
            .background(Tokens.Colors.SurfaceGlass)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 4.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glow = LocalAmbientGlowEnabled.current
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val source = remember { MutableInteractionSource() }
            val pill by animateColorAsState(if (selected) Tokens.Colors.Accent.copy(alpha = 0.2f) else Color.Transparent, tween(250), label = "tabPill")
            val fg = if (selected) Color.White else Tokens.Colors.TextPrimary.copy(alpha = 0.6f)
            Column(
                Modifier.weight(1f)
                    .heightIn(min = Tokens.Size.MobileTabBar)
                    .cvClickable({ onSelect(index) }, role = Role.Tab, haptic = HapticKind.Select, onClickLabel = tab.label, focusShape = RoundedCornerShape(18.dp), pressedScale = 1f, interactionSource = source)
                    .semantics { this.selected = selected },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier.pressScale(source, 0.88f).size(48.dp, 30.dp)
                        .then(if (selected && glow) Modifier.dropShadow(RoundedCornerShape(15.dp), Shadow(24.dp, Tokens.Colors.Accent, (-4).dp)) else Modifier)
                        .clip(RoundedCornerShape(15.dp)).background(pill),
                    contentAlignment = Alignment.Center,
                ) { Icon(tab.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp)) }
                Text(tab.label, style = bodyStyle(10.5.sp, FontWeight.SemiBold), color = fg, maxLines = 1)
            }
        }
    }
}
