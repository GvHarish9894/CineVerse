package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.presentation.RatePrompt
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.haptics.LocalHaptics
import com.techgv.cineverse.ui.theme.displayStyle
import kotlin.math.round

/** Presentation only. Mount once at the app level; the parent owns prompt identity, write state and outcomes. */
@Composable
internal fun MobileRateSheet(
    prompt: RatePrompt,
    pending: Boolean,
    error: String?,
    onSubmit: (Double) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(prompt) {
        mutableFloatStateOf(prompt.initial?.takeIf { it.isFinite() && it in 0.5..10.0 }?.let { (round(it * 2) / 2).toFloat() } ?: 5f)
    }
    val haptics = LocalHaptics.current
    fun select(next: Float, tick: Boolean = true) {
        if (pending || !next.isFinite()) return
        val step = (round(next * 2) / 2).coerceIn(0.5f, 10f)
        if (step != value) {
            value = step
            if (tick) haptics.perform(HapticKind.Tick)
        }
    }
    CvBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("mobile-rate-sheet"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OverlayHeading("YOUR RATING", prompt.title.title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (star in 1..5) {
                    val full = star * 2f
                    val fraction = ((value - (star - 1) * 2) / 2).coerceIn(0f, 1f)
                    Box(Modifier.size(48.dp).cvClickable({ select(if (value == full) full - 1 else full) }, haptic = null, enabled = !pending)
                        .semantics { contentDescription = "Rate ${oneDecimal(full.toDouble())} out of 10; tap again for ${oneDecimal((full - 1).toDouble())}" },
                        contentAlignment = Alignment.Center) {
                        Icon(CvIcons.Star, null, Modifier.size(34.dp), tint = Tokens.Colors.Gold.copy(alpha = 0.4f))
                        Icon(CvIcons.StarFilled, null, Modifier.size(34.dp).drawWithContent {
                            clipRect(right = size.width * fraction) { drawContent() }
                        }, tint = Tokens.Colors.Gold)
                    }
                }
            }
            Text(oneDecimal(value.toDouble()), Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = displayStyle(44.sp), textAlign = TextAlign.Center)
            Text("Out of 10 · half-point steps", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Slider(value, { select(it) }, valueRange = 0.5f..10f, steps = 18, enabled = !pending,
                modifier = Modifier.fillMaxWidth().testTag("mobile-rating-slider")
                    .semantics { contentDescription = "Rating out of 10"; stateDescription = oneDecimal(value.toDouble()) }
                    .onPreviewKeyEvent { event ->
                        if (pending || event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionLeft, Key.DirectionDown -> { select(value - 0.5f); true }
                            Key.DirectionRight, Key.DirectionUp -> { select(value + 0.5f); true }
                            Key.MoveHome -> { select(0.5f); true }
                            Key.MoveEnd -> { select(10f); true }
                            else -> false
                        }
                    })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(CvIcons.ChevronLeft, "Decrease rating by 0.5", { select(value - 0.5f, tick = false) }, haptic = HapticKind.Tick, enabled = !pending && value > 0.5f)
                RoundIconButton(CvIcons.Plus, "Increase rating by 0.5", { select(value + 0.5f, tick = false) }, haptic = HapticKind.Tick, enabled = !pending && value < 10f)
            }
            if (!error.isNullOrBlank()) Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (pending) Text("Saving rating…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            PillButton(if (pending) "Saving…" else "Submit rating", { onSubmit(value.toDouble()) }, Modifier.fillMaxWidth(), enabled = !pending)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (prompt.initial != null) PillButton("Remove rating", onRemove, Modifier.weight(1f), variant = ButtonVariant.Ghost, enabled = !pending)
                PillButton("Cancel", onDismiss, Modifier.weight(1f), variant = ButtonVariant.Ghost)
            }
        }
    }
}
