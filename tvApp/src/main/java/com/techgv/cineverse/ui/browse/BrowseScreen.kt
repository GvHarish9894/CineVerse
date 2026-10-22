package com.techgv.cineverse.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.ui.components.AmbientBackdrop
import com.techgv.cineverse.ui.components.Chip
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.ImageSize
import com.techgv.cineverse.ui.components.Section
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.UiColors
import com.techgv.cineverse.ui.components.bleedStart
import com.techgv.cineverse.ui.components.rememberSavedLazyGridState
import com.techgv.cineverse.ui.components.tmdbImageUrl
import com.techgv.cineverse.ui.nav.BrowseKind
import com.techgv.cineverse.ui.nav.Route

private const val PANEL = "browse"
private const val TABS = "tabs"
private const val GRID = "grid"
private const val COLUMNS = 7

/** Prototype content inset from the collapsed rail (x 120 = rail 88 + 32). */
private val CONTENT_INSET = 32.dp

/** §7.3 Browse: title + tab chips (switch on focus, OK enters the grid) + 7-column paged grid. */
@Composable
fun BrowseScreen(container: AppContainer, kind: BrowseKind, onOpen: (Route) -> Unit) {
    val vm: BrowseViewModel = viewModel(key = "browse:$kind") {
        BrowseViewModel(container.tmdbRepository, container.appGeneration, container.settingsRepository.settings, createSavedStateHandle(), kind)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val restorer = remember(vm) { FocusRestorer(vm.focusMemory) }
    val gridState = rememberSavedLazyGridState(vm.focusMemory, GRID)
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf<Hero?>(null) }

    // A new tab starts at the top; the first composition keeps the saved scroll (return / process death).
    var shownTab by remember { mutableIntStateOf(state.tab) }
    LaunchedEffect(state.tab) {
        if (state.tab != shownTab) {
            shownTab = state.tab
            gridState.scrollToItem(0)
        }
    }

    val items = (state.grid as? SectionState.Content)?.value?.items.orEmpty()
    // Only the selected chip is an entry point: focusing another chip would switch the tab.
    val tabsRow = FocusRow(PANEL, TABS, listOf(tabKey(state.tab)))
    val gridRow = FocusRow(PANEL, GRID, items.map { it.key }, gridState = gridState)
    var restored by remember { mutableStateOf(false) }
    // Chips switch tabs on focus only after the restore: incidental default focus must not switch the tab.
    var focusSettled by remember { mutableStateOf(false) }
    val settled = state.grid !is SectionState.Loading
    LaunchedEffect(settled) {
        if (settled && !restored) {
            restored = true
            restorer.restore(listOf(tabsRow, gridRow))
            focusSettled = true
        }
    }

    Box(Modifier.fillMaxSize().background(UiColors.Background)) {
        AmbientBackdrop(focused?.let { artUrl(it) }, focused?.key ?: "browse:$kind")
        Column(Modifier.fillMaxSize().padding(top = 44.dp)) {
            Text(
                browseTitle(kind),
                modifier = Modifier.padding(start = CONTENT_INSET),
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                vm.tabs[state.tab].subtitle,
                modifier = Modifier.padding(start = CONTENT_INSET, top = 8.dp),
                color = UiColors.TextDim,
                fontSize = 15.sp,
            )
            Row(
                with(restorer) { Modifier.restoreRowFocus(PANEL, TABS) }.padding(start = CONTENT_INSET, top = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vm.tabs.forEachIndexed { index, tab ->
                    Chip(
                        label = tab.label,
                        selected = index == state.tab,
                        onClick = { focusManager.moveFocus(FocusDirection.Down) },
                        onFocused = { if (focusSettled) vm.selectTab(index) },
                        modifier = with(restorer) { Modifier.rememberFocus(PANEL, TABS, tabKey(index), index) },
                    )
                }
            }
            Section(state.grid, vm::retry, Modifier.weight(1f).padding(start = CONTENT_INSET, top = 8.dp)) { paged ->
                HeroGrid(
                    paged = paged,
                    columns = COLUMNS,
                    gridState = gridState,
                    restorer = restorer,
                    focusRow = gridRow,
                    onLoadMore = vm::loadMore,
                    onFocused = { focused = it },
                    onOpen = { onOpen(it.route()) },
                    contentPadding = PaddingValues(start = CONTENT_INSET, top = 18.dp, bottom = 48.dp, end = CONTENT_INSET),
                    modifier = Modifier.bleedStart(CONTENT_INSET),
                    personRole = { it.knownForLine() },
                )
            }
        }
    }
}

private fun tabKey(index: Int) = "tab$index"

@Composable
private fun artUrl(hero: Hero): String? = when (hero) {
    is Hero.OfTitle -> tmdbImageUrl(hero.title.posterPath, ImageSize.POSTER)
    is Hero.OfPerson -> tmdbImageUrl(hero.person.profilePath, ImageSize.PROFILE)
}
