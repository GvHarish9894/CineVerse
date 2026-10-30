package com.techgv.cineverse.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.haptics.LocalHaptics
import com.techgv.cineverse.ui.theme.LocalReduceMotion

/** cubic-bezier(.2,.8,.2,1): the kits' standard "emphasized" curve (tap, hover lift, enter). */
val EmphasizedEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** Minimum touch target: controls with a smaller visual sit inside a box at least this big. */
val MinTouchTarget = 48.dp

/** Disabled controls fade to Material's 38% (semantics come from `cvClickable(enabled = false)`). */
fun Modifier.disabledAlpha(enabled: Boolean): Modifier = if (enabled) this else alpha(0.38f)

/** Keyboard focus ring from the web kit: 2px white outline, 3px outside the element, following its [shape]. */
fun Modifier.focusRing(interactionSource: InteractionSource, shape: Shape): Modifier = composed {
    val focused by interactionSource.collectIsFocusedAsState()
    drawWithContent {
        drawContent()
        if (focused) {
            val width = 2.dp.toPx()
            val inset = 3.dp.toPx() + width / 2
            val outline = shape.createOutline(Size(size.width + 2 * inset, size.height + 2 * inset), layoutDirection, this)
            translate(-inset, -inset) { drawOutline(outline, Tokens.Colors.Focus, style = Stroke(width)) }
        }
    }
}

/** Every tappable scales to [pressedScale] in 160 ms while pressed (mobile kit); off under reduce motion. */
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = Tokens.Motion.PressScale): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(if (pressed && !reduceMotion) pressedScale else 1f, tween(160, easing = EmphasizedEasing), label = "pressScale")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The house clickable: press-scale, keyboard focus ring, hover tracking, hand cursor, haptic and semantics role in
 * one place. Disabled: not focusable, no click/haptic, announced as disabled. Pass [interactionSource] when the
 * caller also reacts to hover/focus (cards).
 */
fun Modifier.cvClickable(
    onClick: () -> Unit,
    role: Role = Role.Button,
    haptic: HapticKind? = HapticKind.Tap,
    onClickLabel: String? = null,
    focusShape: Shape = RoundedCornerShape(Tokens.Radius.Card),
    pressedScale: Float = Tokens.Motion.PressScale,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = LocalHaptics.current
    pressScale(source, pressedScale)
        .focusRing(source, focusShape)
        .hoverable(source, enabled)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .clickable(source, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role) {
            haptic?.let(haptics::perform)
            onClick()
        }
}

/** Hovered (pointer) or focused (keyboard): the web kit drives lift/glow/quick-action from one flag. */
@Composable
fun InteractionSource.collectIsActiveAsState(): State<Boolean> {
    val hovered = collectIsHoveredAsState()
    val focused = collectIsFocusedAsState()
    return remember { derivedStateOf { hovered.value || focused.value } }
}
