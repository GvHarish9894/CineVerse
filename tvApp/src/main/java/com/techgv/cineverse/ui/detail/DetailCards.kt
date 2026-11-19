package com.techgv.cineverse.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.PinToStart
import com.techgv.cineverse.ui.components.cardRowHeight
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val SCREEN_INSET = 48.dp

/** Where a focused row's cards land below the viewport top: leaves its title visible. */
internal val ROW_TOP_INSET = 80.dp

/** Vertical padding of a [DetailRow]'s cards. */
private val ROW_PADDING = 16.dp

/** Title + subtitle lines under a poster / person / season card. */
private val CARD_LABELS_HEIGHT = 56.dp

/** Hero metadata chip (prototype `.mchip`), not focusable; the §6 [com.techgv.cineverse.ui.components.Chip] is a tab / filter. */
@Composable
internal fun DetailChip(label: String) {
    Box(
        Modifier.height(44.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * A titled horizontal row (§1.4): LazyRow pinned to the start inset, with per-row focus memory
 * (entering from another row lands on the remembered item, else `min(savedIndex, lastIndex)`).
 */
@Composable
internal fun DetailRow(
    title: String,
    restorer: FocusRestorer,
    focusRow: FocusRow,
    state: LazyListState,
    /** Unfocused height of the row's cards and of the labels under them: the row is pinned to [cardRowHeightOf]. */
    cardHeight: Dp,
    labels: Dp = 0.dp,
    spacing: Int = 16,
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = SCREEN_INSET, bottom = 4.dp),
        )
        PinToStart(SCREEN_INSET) {
            LazyRow(
                state = state,
                modifier = with(restorer) { Modifier.restoreRowFocus(focusRow, scope) }
                    .cardRowHeight(cardHeight, labels, ROW_PADDING, ROW_PADDING),
                contentPadding = PaddingValues(horizontal = SCREEN_INSET, vertical = ROW_PADDING),
                horizontalArrangement = Arrangement.spacedBy(spacing.dp),
                content = content,
            )
        }
    }
}

/**
 * Wrap the outer vertical list: it scrolls only when the focused child is not fully visible, then puts its
 * top [topInset] below the viewport top (room for the row title). The TV default pivot would scroll even
 * the hero's buttons to 30 % and cut the title off. Rows inside keep their own [PinToStart].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RevealVertically(topInset: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val insetPx = with(density) { topInset.toPx() }
    val labelsPx = with(density) { CARD_LABELS_HEIGHT.toPx() }
    val spec = remember(insetPx, labelsPx) {
        object : BringIntoViewSpec {
            // The focused card's labels sit below it: they must fit too.
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) =
                if (offset >= 0f && offset + size + labelsPx <= containerSize) 0f else offset - insetPx
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Focus entering the hero (outer item 0) shows all of it; the TV pivot alone would cut the title off.
 * The focused button's own bring-into-view is swallowed: it would cancel this scroll and stop with the hero half shown.
 */
internal fun Modifier.revealHeroOnFocus(outer: LazyListState, scope: CoroutineScope, reduceMotion: Boolean): Modifier =
    onFocusChanged { if (it.hasFocus) scope.launch { if (reduceMotion) outer.scrollToItem(0) else outer.animateScrollToItem(0) } }
        .then(SwallowBringIntoView)

private data object SwallowBringIntoView : ModifierNodeElement<SwallowBringIntoViewNode>() {
    override fun create() = SwallowBringIntoViewNode()
    override fun update(node: SwallowBringIntoViewNode) = Unit
}

private class SwallowBringIntoViewNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) = Unit
}

internal fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
