package com.techgv.cineverse.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /** Signed-in display name; null = guest (§5, D3: initials only, no photo). */
    accountName: String?,
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
        RailAccount(accountName, expanded)
    }
}

/** Up to two initials of [name] ("Harish Gv" → "HG"); "G" for the guest. */
internal fun accountInitials(name: String?): String =
    name?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.take(2)?.joinToString("") { it.first().uppercase() }
        ?.ifEmpty { null } ?: "G"

@Composable
private fun RailAccount(accountName: String?, expanded: Boolean) {
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(
                if (accountName != null) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                accountInitials(accountName),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (accountName != null) MaterialTheme.colorScheme.onPrimary else Color.White,
            )
        }
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(accountName?.let { "$it · TMDB" } ?: "Guest", fontSize = 14.sp, maxLines = 1)
        }
    }
}
