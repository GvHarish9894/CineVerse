package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * How far a card [cardHeight] tall grows above and below itself when focused at [scale]. Focus scales from the
 * card's centre at draw time (graphicsLayer), so this lands in the row's padding, not in its layout.
 */
fun focusGrowth(cardHeight: Dp, scale: Float = CARD_FOCUS_SCALE): Dp = cardHeight * ((scale - 1f) / 2f)

/**
 * Height of a horizontal row of cards [cardHeight] tall with [labels] under them, inside vertical padding [top] /
 * [bottom]. The padding must hold the focused card's [focusGrowth] on both sides.
 */
fun cardRowHeightOf(cardHeight: Dp, labels: Dp, top: Dp, bottom: Dp, scale: Float = CARD_FOCUS_SCALE): Dp {
    val growth = focusGrowth(cardHeight, scale)
    require(top >= growth && bottom >= growth) { "Row padding $top / $bottom can't hold a focused card's growth of $growth" }
    return top + cardHeight + labels + bottom
}

/**
 * Pins a card row to [cardRowHeightOf] (min == max): focus moving, a card without a subtitle, reaching the last
 * item, or the row switching between loading / error / content never changes its height, so nothing below it moves.
 */
fun Modifier.cardRowHeight(cardHeight: Dp, labels: Dp, top: Dp, bottom: Dp, scale: Float = CARD_FOCUS_SCALE): Modifier {
    val height = cardRowHeightOf(cardHeight, labels, top, bottom, scale)
    return heightIn(min = height, max = height)
}
