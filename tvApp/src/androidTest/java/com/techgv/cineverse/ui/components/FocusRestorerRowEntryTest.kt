package com.techgv.cineverse.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [FocusRestorer.restoreRowFocus] with real D-pad focus moves between two lazy rows. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FocusRestorerRowEntryTest {
    @get:Rule
    val compose = createComposeRule()

    private val restorer = FocusRestorer(FocusMemory(SavedStateHandle()))

    @Test
    fun reenteringRowWhoseRememberedItemWasRemoved_focusesItemAtSavedIndexWithoutCrash() {
        var rowA by mutableStateOf((0..5).map { "a$it" })
        compose.setContent {
            val scope = rememberCoroutineScope()
            val stateA = rememberLazyListState()
            Column {
                LazyRow(
                    state = stateA,
                    modifier = with(restorer) { Modifier.restoreRowFocus(FocusRow(PANEL, "A", rowA, stateA), scope) },
                ) {
                    itemsIndexed(rowA, key = { _, key -> key }) { index, key -> Item("A", key, index) }
                }
                LazyRow(modifier = with(restorer) { Modifier.restoreRowFocus(PANEL, "B") }) {
                    itemsIndexed(listOf("b0", "b1"), key = { _, key -> key }) { index, key -> Item("B", key, index) }
                }
            }
        }

        compose.onNodeWithTag("a2").requestFocus()
        compose.onNodeWithTag("a2").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        val focusedInB = listOf("b0", "b1").first { runCatching { compose.onNodeWithTag(it).assertIsFocused() }.isSuccess }

        rowA = rowA - "a2" // the remembered item leaves the data (and composition); the row key stays
        compose.waitForIdle()
        compose.onNodeWithTag(focusedInB).performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()

        // min(savedIndex = 2, lastIndex) → "a3" now sits at index 2.
        compose.onNodeWithTag("a3").assertIsFocused()
    }

    @androidx.compose.runtime.Composable
    private fun Item(rowKey: String, key: String, index: Int) {
        Box(with(restorer) { Modifier.rememberFocus(PANEL, rowKey, key, index) }.testTag(key).size(80.dp).focusable())
    }

    private companion object {
        const val PANEL = "rows"
    }
}
