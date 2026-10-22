package com.techgv.cineverse.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.Person
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.presentation.PagedItems
import com.techgv.cineverse.ui.components.PersonCard
import com.techgv.cineverse.ui.components.PinToStart
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.UiColors
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.ui.components.message

/** Route of a grid / row item: title → Detail, person → Person. */
fun Hero.route(): Route = when (this) {
    is Hero.OfTitle -> Route.Detail(title.mediaType, title.ref.id)
    is Hero.OfPerson -> Route.Person(person.id)
}

/** First known-for title: the People grid's sub-line (prototype `personItem(p, known[0].title)`). */
fun Person.knownForLine(): String = knownFor.firstOrNull()?.title.orEmpty()

/**
 * Paged grid of titles (PosterCard) and people (PersonCard) in list order (§7.3 / §7.7). The next page
 * loads once the last row is visible; its loading / error footer spans the grid width.
 */
@Composable
fun HeroGrid(
    paged: PagedItems,
    columns: Int,
    gridState: LazyGridState,
    restorer: FocusRestorer,
    focusRow: FocusRow,
    onLoadMore: () -> Unit,
    onFocused: (Hero) -> Unit,
    onOpen: (Hero) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    personRole: (Person) -> String = { it.knownForDepartment.orEmpty() },
) {
    val scope = rememberCoroutineScope()
    val itemCount by rememberUpdatedState(paged.items.size)
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= itemCount - columns) loadMore() }
    }
    PinToStart(leadingInset = contentPadding.calculateTopPadding()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = with(restorer) { modifier.restoreRowFocus(focusRow, scope) },
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(paged.items, key = { _, item -> item.key }) { index, item ->
                val itemModifier = with(restorer) { Modifier.rememberFocus(focusRow.panelKey, focusRow.rowKey, item.key, index) }
                    .onFocusChanged { if (it.isFocused) onFocused(item) }
                when (item) {
                    is Hero.OfTitle -> PosterCard(item.title, { onOpen(item) }, itemModifier)
                    is Hero.OfPerson -> PersonCard(
                        name = item.person.name,
                        role = personRole(item.person),
                        profilePath = item.person.profilePath,
                        seed = item.key,
                        onClick = { onOpen(item) },
                        modifier = itemModifier,
                    )
                }
            }
            val error = paged.moreError
            if (paged.loadingMore || error != null) {
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                    if (error == null) {
                        Text("Loading more…", color = UiColors.TextFaint, modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(error.message, color = UiColors.TextDim, modifier = Modifier.padding(vertical = 16.dp))
                            if (error.retryable) Button(onClick = onLoadMore) { Text("Try again") }
                        }
                    }
                }
            }
        }
    }
}
