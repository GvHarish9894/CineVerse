package com.techgv.cineverse.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.ui.browse.HeroGrid
import com.techgv.cineverse.ui.browse.route
import com.techgv.cineverse.ui.components.AmbientBackdrop
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.ImageSize
import com.techgv.cineverse.ui.components.KEYBOARD_ROWS
import com.techgv.cineverse.ui.components.KeyboardKey
import com.techgv.cineverse.ui.components.OnScreenKeyboard
import com.techgv.cineverse.ui.components.Section
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.UiColors
import com.techgv.cineverse.ui.components.bleedStart
import com.techgv.cineverse.ui.components.rememberSavedLazyGridState
import com.techgv.cineverse.ui.components.tmdbImageUrl
import com.techgv.cineverse.ui.nav.Route

private const val KEYBOARD_PANEL = "kbd"
private const val KEYS = "keys"
private const val RESULTS_PANEL = "res"
private const val RESULTS_GRID = "results"
private const val COLUMNS = 4
private val CONTENT_INSET = 32.dp
private const val NO_RESULTS = "Nothing on TMDB matches that yet. Try a shorter word, or a person’s name."

/**
 * §7.7 Search: query field + keyboard on the left, one mixed result grid (API order) on the right.
 * Hardware keyboard characters and DEL also edit the query, wherever focus is on this screen.
 */
@Composable
fun SearchScreen(container: AppContainer, onOpen: (Route) -> Unit) {
    val vm: SearchViewModel = viewModel {
        SearchViewModel(container.tmdbRepository, container.appGeneration, container.settingsRepository.settings, createSavedStateHandle())
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val restorer = remember(vm) { FocusRestorer(vm.focusMemory) }
    val gridState = rememberSavedLazyGridState(vm.focusMemory, RESULTS_GRID)
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<Hero?>(null) }
    var keyboardFocused by remember { mutableStateOf(false) }

    val query = state.query.trim()
    // Results focus memory is per query: a new query's results are entered at the first result.
    val resultsRowKey = "$RESULTS_GRID:$query"
    var shownQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != shownQuery) {
            shownQuery = query
            gridState.scrollToItem(0)
        }
    }

    val keyIds = remember { KEYBOARD_ROWS.flatten().map { it.id } }
    val keyboardRow = FocusRow(KEYBOARD_PANEL, KEYS, keyIds)
    val items = (state.results as? SectionState.Content)?.value?.items.orEmpty()
    val resultsRow = FocusRow(RESULTS_PANEL, resultsRowKey, items.map { it.key }, gridState = gridState)
    var restored by remember { mutableStateOf(false) }
    val settled = state.results !is SectionState.Loading
    LaunchedEffect(settled) {
        if (settled && !restored) {
            restored = true
            restorer.restore(listOf(keyboardRow, resultsRow))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(UiColors.Background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Backspace || event.key == Key.Delete -> vm.onKey(KeyboardKey.Delete).let { true }
                    else -> {
                        val char = event.utf16CodePoint.toChar()
                        if (char.isLetterOrDigit() || char == ' ') vm.onTyped(char).let { true } else false
                    }
                }
            },
    ) {
        AmbientBackdrop(focused?.let { artUrl(it) }, focused?.key ?: "search")
        Row(Modifier.fillMaxSize().padding(top = 44.dp)) {
            Column(Modifier.padding(start = CONTENT_INSET).width(400.dp)) {
                QueryField(state.query, active = keyboardFocused)
                Spacer(Modifier.height(22.dp))
                OnScreenKeyboard(
                    onKey = vm::onKey,
                    modifier = with(restorer) { Modifier.restoreRowFocus(keyboardRow, scope) }
                        .onFocusChanged { keyboardFocused = it.hasFocus },
                    keyModifier = { id, index -> with(restorer) { Modifier.rememberFocus(KEYBOARD_PANEL, KEYS, id, index) } },
                )
                Text(
                    "Tip: type on your keyboard too. Backspace deletes.",
                    modifier = Modifier.padding(top = 10.dp),
                    color = UiColors.TextFaint,
                    fontSize = 13.sp,
                )
            }
            Column(Modifier.padding(start = 40.dp).weight(1f).fillMaxHeight()) {
                Text(
                    if (state.searching) "Results for “$query”" else "Trending now",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.searching) "Movies, TV shows and people" else "What people are looking up on TMDB this week",
                    modifier = Modifier.padding(top = 6.dp),
                    color = UiColors.TextDim,
                    fontSize = 14.sp,
                )
                Section(
                    state.results,
                    vm::retry,
                    Modifier.weight(1f),
                    empty = { Text(NO_RESULTS, color = UiColors.TextDim, fontSize = 16.sp, modifier = Modifier.width(460.dp)) },
                ) { paged ->
                    HeroGrid(
                        paged = paged,
                        columns = COLUMNS,
                        gridState = gridState,
                        restorer = restorer,
                        focusRow = resultsRow,
                        onLoadMore = vm::loadMore,
                        onFocused = { focused = it },
                        onOpen = { onOpen(it.route()) },
                        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 48.dp, end = 32.dp),
                        modifier = Modifier.bleedStart(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueryField(query: String, active: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0x14FFFFFF), RoundedCornerShape(16.dp))
            .border(2.dp, if (active) UiColors.Accent else Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp)
            .semantics {
                contentDescription = "Search query: ${query.ifEmpty { "empty" }}"
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⌕", color = UiColors.TextDim, fontSize = 22.sp)
        if (query.isNotEmpty()) Text(query, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Box(Modifier.size(2.dp, 24.dp).background(UiColors.Accent))
        if (query.isEmpty()) Text("Movies, TV shows, people", color = UiColors.TextFaint, fontSize = 19.sp, maxLines = 1)
    }
}

@Composable
private fun artUrl(hero: Hero): String? = when (hero) {
    is Hero.OfTitle -> tmdbImageUrl(hero.title.posterPath, ImageSize.POSTER)
    is Hero.OfPerson -> tmdbImageUrl(hero.person.profilePath, ImageSize.PROFILE)
}
