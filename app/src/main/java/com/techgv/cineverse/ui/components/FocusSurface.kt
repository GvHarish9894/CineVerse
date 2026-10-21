package com.techgv.cineverse.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Prototype colours shared by the browse components. */
object UiColors {
    val Background = Color(0xFF07060F)
    val Text = Color(0xFFF4F2FF)
    val TextDim = Color(0xBDF4F2FF)
    val TextFaint = Color(0x94F4F2FF)
    val Accent = Color(0xFFFF3D7F)
    val Tile = Color(0x1AFFFFFF)
    val Badge = Color(0xFFFFC24B)
}

const val FOCUS_MILLIS = 150
const val CARD_FOCUS_SCALE = 1.08f

/**
 * The focusable, clickable frame of every card / chip / button / key (§6): scale [focusedScale] and a
 * [glowColor] glow over 150 ms when focused. Reduce motion → the scale snaps; Ambient glow off → no
 * coloured glow, the white focus border always stays. Put `rememberFocus(...)` in [modifier] (it must
 * precede the focus target).
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    glowColor: Color = Color.Unspecified,
    focusedScale: Float = CARD_FOCUS_SCALE,
    enabled: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val settings = LocalSettings.current
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = if (settings.motion) snap() else tween(FOCUS_MILLIS),
        label = "focusScale",
    )
    val glow = focused && settings.glow && glowColor.isSpecified
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (glow) Modifier.shadow(28.dp, shape, ambientColor = glowColor, spotColor = glowColor) else Modifier)
            .clip(shape)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            // Disabled stays focusable (focus must not jump away from a button while it is pending).
            .semantics { if (!enabled) disabled() }
            .clickable(interactionSource = null, indication = null, onClick = { if (enabled) onClick() }),
    ) {
        content(focused)
    }
}

/**
 * Widens a lazy list / grid by [inset] to the start, past its parent's padding, so a focused first card
 * scaled by [FocusSurface] is not clipped while messages of the same section keep the padding.
 */
fun Modifier.bleedStart(inset: Dp): Modifier = layout { measurable, constraints ->
    val extra = inset.roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}
