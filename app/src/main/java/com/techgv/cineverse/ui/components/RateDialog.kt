package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import java.util.Locale

/** §7.11 word label. */
fun ratingWord(value: Double): String = when {
    value <= 3.0 -> "Poor"
    value <= 5.0 -> "Meh"
    value <= 7.0 -> "Good"
    value <= 8.5 -> "Great"
    else -> "Masterpiece"
}

fun formatRating(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * §7.11 Rate: slider 0.5–10 in 0.5 steps (LEFT / RIGHT), default [initial] or 7.0; OK on the slider submits;
 * Submit / Remove (only when rated) / Cancel; BACK cancels without writing.
 */
@Composable
fun RateDialog(
    titleName: String,
    initial: Double?,
    onSubmit: (Double) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    var value by rememberSaveable { mutableDoubleStateOf(initial ?: DEFAULT_RATING) }
    val slider = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel) { DesignCanvas {
        Column(
            Modifier.width(560.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Rate on TMDB", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(titleName, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            RatingSlider(value, onChange = { value = it }, onOk = { onSubmit(value) }, modifier = Modifier.focusRequester(slider))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { onSubmit(value) }) { Text("Submit rating") }
                if (initial != null) OutlinedButton(onClick = onRemove) { Text("Remove rating") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    } }
    LaunchedEffect(Unit) { slider.requestFocus() }
}

@Composable
private fun RatingSlider(value: Double, onChange: (Double) -> Unit, onOk: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .testTag(RATING_SLIDER_TAG)
            .semantics {
                contentDescription = "Rating ${formatRating(value)} out of 10"
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), MIN_RATING.toFloat()..MAX_RATING.toFloat(), STEPS)
            }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) {
                            val step = if (event.key == Key.DirectionRight) STEP else -STEP
                            onChange((value + step).coerceIn(MIN_RATING, MAX_RATING))
                        }
                        true
                    }
                    // Submit on key up so the release never reaches the invoker after the dialog closes.
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyUp) onOk()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .border(2.dp, if (focused) accent else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(Modifier.weight(1f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))) {
            Box(Modifier.fillMaxWidth((value / MAX_RATING).toFloat()).fillMaxHeight().background(accent, RoundedCornerShape(6.dp)))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatRating(value), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("${ratingWord(value)} · out of 10", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

const val RATING_SLIDER_TAG = "rateSlider"
private const val DEFAULT_RATING = 7.0
private const val MIN_RATING = 0.5
private const val MAX_RATING = 10.0
private const val STEP = 0.5
private const val STEPS = 18 // 0.5…10 in 0.5 steps: 20 values, 18 between the ends
