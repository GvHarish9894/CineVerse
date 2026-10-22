package com.techgv.cineverse.ui.spikes

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.PinToStart
import com.techgv.cineverse.ui.components.rememberSavedLazyListState
import com.techgv.cineverse.ui.nav.Route

class FocusSpikeViewModel(handle: SavedStateHandle) : ViewModel() {
    val focusMemory = FocusMemory(handle)
}

private const val PANEL = "rows"
private val SPIKE_ROWS = listOf("popularMovies" to (1..20).toList(), "topRated" to (101..120).toList())

/** Spike c: two rows × 20 cards → push Detail → pop → focus returns to the same card (also after process death). */
@Composable
fun FocusSpikeScreen(onOpen: (Route) -> Unit) {
    val vm: FocusSpikeViewModel = viewModel { FocusSpikeViewModel(createSavedStateHandle()) }
    val restorer = remember(vm) { FocusRestorer(vm.focusMemory) }
    val outer = rememberSavedLazyListState(vm.focusMemory, "outer")
    val rowStates = SPIKE_ROWS.map { (rowKey, _) -> rememberSavedLazyListState(vm.focusMemory, rowKey) }

    LazyColumn(state = outer, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        itemsIndexed(SPIKE_ROWS, key = { _, row -> row.first }) { rowIndex, (rowKey, ids) ->
            Text(rowKey, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 48.dp, top = 16.dp))
            PinToStart(leadingInset = 48.dp) {
                LazyRow(
                    state = rowStates[rowIndex],
                    modifier = with(restorer) { Modifier.restoreRowFocus(PANEL, rowKey) },
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                        Card(
                            onClick = { onOpen(Route.Detail(MediaType.Movie, id)) },
                            modifier = with(restorer) { Modifier.rememberFocus(PANEL, rowKey, "movie:$id", index) }.size(140.dp, 210.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("#$id") }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val rows = SPIKE_ROWS.mapIndexed { i, (rowKey, ids) -> FocusRow(PANEL, rowKey, ids.map { "movie:$it" }, rowStates[i]) }
        val restored = restorer.restore(rows, outer)
        Log.i("FocusSpike", "restore saved=${vm.focusMemory.saved} focused=$restored")
    }
}
