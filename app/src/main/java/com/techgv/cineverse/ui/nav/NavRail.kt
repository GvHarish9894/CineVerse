package com.techgv.cineverse.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

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

/** Collapsed glyphs; labels while the rail has focus. Shown only on rail screens (see [isRailScreen]). */
@Composable
fun NavRail(
    current: RailItem?,
    onSelect: (RailItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(if (expanded) 220.dp else 72.dp)
            .background(MaterialTheme.colorScheme.surface)
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = 24.dp, horizontal = 12.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        RailItem.entries.forEach { item ->
            Surface(
                onClick = { onSelect(item) },
                shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (item == current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                ),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.glyph, style = MaterialTheme.typography.titleMedium)
                    if (expanded) {
                        Spacer(Modifier.width(16.dp))
                        Text(item.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}
