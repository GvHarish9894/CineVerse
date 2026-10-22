package com.techgv.cineverse.ui.nav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.techgv.cineverse.ui.components.FocusSurface
import com.techgv.cineverse.ui.components.LocalSettings
import com.techgv.cineverse.ui.components.UiColors
import com.techgv.cineverse.ui.components.initials
import com.techgv.cineverse.presentation.BrowseKind

/** Rail items in prototype order (§5). */
enum class RailItem(val label: String, val glyph: String, val route: Route) {
    Search("Search", "⌕", Route.Search),
    Home("Home", "⌂", Route.Home),
    Movies("Movies", "▶", Route.Browse(BrowseKind.Movies)),
    TvShows("TV Shows", "▭", Route.Browse(BrowseKind.Tv)),
    People("People", "◉", Route.Browse(BrowseKind.People)),
    Library("My Library", "♡", Route.Library),
    Settings("Settings", "⚙", Route.Settings),
}

/** Collapsed rail width; rail screens inset their content by it (the expanded rail overlays content). */
val RAIL_COLLAPSED_WIDTH = 88.dp
private val RAIL_EXPANDED_WIDTH = 268.dp

/**
 * §5 rail: glyphs while collapsed; expands with labels while it has focus. Focus entering the rail lands
 * on [current]. Bottom: initials + [accountName], or "Guest" (D3: initials only, no photo).
 */
@Composable
fun NavRail(
    current: RailItem?,
    onSelect: (RailItem) -> Unit,
    modifier: Modifier = Modifier,
    /** Signed-in display name; null = guest. Initials are derived here (D3: no photo). */
    accountName: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val reduceMotion = LocalSettings.current.motion
    val width by animateDpAsState(
        if (expanded) RAIL_EXPANDED_WIDTH else RAIL_COLLAPSED_WIDTH,
        if (reduceMotion) snap() else tween(300),
        label = "railWidth",
    )
    val requesters = remember { RailItem.entries.associateWith { FocusRequester() } }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(if (expanded) Color(0xF00E0C1C) else Color.Transparent)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties {
                onEnter = { current?.let { runCatching { requesters.getValue(it).requestFocus() } } }
            }
            .focusGroup()
            .padding(vertical = 30.dp, horizontal = 14.dp),
    ) {
        Row(Modifier.height(44.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(UiColors.Accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 18.sp)
            }
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text("CineVerse", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            RailItem.entries.forEach { item ->
                RailButton(item, item == current, expanded, { onSelect(item) }, Modifier.focusRequester(requesters.getValue(item)))
            }
        }
        Row(Modifier.height(44.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val name = accountName?.takeIf { it.isNotBlank() } ?: "Guest"
            Box(Modifier.size(40.dp).background(Color(0x33FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Text(initials(accountName.orEmpty()).ifEmpty { "G" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RailButton(item: RailItem, isCurrent: Boolean, expanded: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1f,
        modifier = modifier.fillMaxWidth().height(50.dp).semantics {
            contentDescription = item.label
            selected = isCurrent
        },
    ) { focused ->
        val content = if (focused) UiColors.Background else if (isCurrent) Color.White else UiColors.TextDim
        Row(
            Modifier.fillMaxWidth().height(50.dp).background(if (focused) Color.White else Color.Transparent).padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(18.dp).background(if (isCurrent) UiColors.Accent else Color.Transparent, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(10.dp))
            Text(item.glyph, color = content, fontSize = 20.sp)
            if (expanded) {
                Spacer(Modifier.width(18.dp))
                Text(item.label, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
