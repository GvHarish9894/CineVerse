package com.techgv.cineverse.ui.web

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.presentation.LibraryActionsStateHolder
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle
import kotlin.math.ceil
import kotlin.math.round

/**
 * Web Rate dialog (SCREENS §10): host once above routes. Shown only while [actions] has a prompt; a prompt cleared by
 * the holder (identity change, Player up) closes it without writing. Writes go only through the holder, whose
 * onOutcome (root-owned) reports them: no toast here.
 */
@Composable
fun WebRateDialog(actions: LibraryActionsStateHolder, restoreFocus: FocusRequester, onRestoreFocus: (() -> Unit)? = null) {
    val prompt by actions.ratePrompt.collectAsState(null)
    val p = prompt ?: return
    var chosen by remember(p) { mutableStateOf(p.initial) }
    var hover by remember(p) { mutableStateOf<Double?>(null) }
    val shown = hover ?: chosen
    val submit = { chosen?.let(actions::submitRating) }
    CvDialog(
        onDismissRequest = actions::closeRate,
        title = p.title.title,
        kicker = if (p.identity != null) "Rate on TMDB" else "Rate in this browser",
        restoreFocus = restoreFocus,
        onRestoreFocus = onRestoreFocus,
        modifier = Modifier.testTag("web-rate-dialog"),
    ) {
        Spacer(Modifier.height(22.dp))
        RatingStars(shown, chosen, onHover = { hover = it }, onChoose = { chosen = it }, onSubmit = { submit() }, onDismiss = actions::closeRate)
        Text(
            buildAnnotatedString {
                append(shown?.let(::oneDecimal) ?: "–")
                withStyle(SpanStyle(fontSize = 20.sp, color = Tokens.Colors.TextPrimary.copy(alpha = 0.55f))) { append(" / 10") }
            },
            style = displayStyle(46.sp, trackingEm = -0.03f),
            modifier = Modifier.padding(top = 16.dp).testTag("web-rate-value"),
        )
        Text(shown?.let(::ratingWord) ?: "Choose a rating", style = bodyStyle(14.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp).testTag("web-rate-word"))
        FlowRow(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Submit rating", { submit() }, enabled = chosen != null)
            if (p.initial != null) PillButton("Remove rating", actions::removeRating, variant = ButtonVariant.Ghost)
        }
    }
}

/** 5 stars, 0.5..10 in 0.5 steps: pointer x previews (hover) and chooses (release); ←/→/↑/↓ ±0.5, Home/End, Enter submits. */
@Composable
private fun RatingStars(shown: Double?, chosen: Double?, onHover: (Double?) -> Unit, onChoose: (Double) -> Unit, onSubmit: () -> Unit, onDismiss: () -> Unit) {
    val hover by rememberUpdatedState(onHover)
    val choose by rememberUpdatedState(onChoose)
    val source = remember { MutableInteractionSource() }
    val change: (Double) -> Unit = { target ->
        hover(null)
        choose((round(target * 2) / 2.0).coerceIn(MIN_RATING, MAX_RATING))
    }
    Row(
        webDialogSlider(chosen, chosen?.let { "${oneDecimal(it)}, ${ratingWord(it)}" } ?: "Not chosen", change, onSubmit, onDismiss)
            .testTag("web-rate-stars")
            .focusRing(source, RoundedCornerShape(12.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val at = ratingAt(event.changes.first().position.x, size.width.toFloat())
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> hover(at)
                            PointerEventType.Exit -> hover(null)
                            PointerEventType.Release -> { choose(at); hover(null) }
                        }
                    }
                }
            }
            .onKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionRight, Key.DirectionUp -> 0.5
                    Key.DirectionLeft, Key.DirectionDown -> -0.5
                    Key.MoveHome -> -MAX_RATING
                    Key.MoveEnd -> MAX_RATING
                    Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyDown) { hover(null); onSubmit() } // keys act on chosen, never on a stale hover preview
                        return@onKeyEvent true
                    }
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) change((chosen ?: 0.0) + step)
                true
            }
            .semantics {
                contentDescription = "Rating out of 10"
                stateDescription = chosen?.let { "${oneDecimal(it)}, ${ratingWord(it)}" } ?: "Not chosen"
                progressBarRangeInfo = ProgressBarRangeInfo((chosen ?: 0.0).toFloat(), 0f..MAX_RATING.toFloat(), steps = 19)
                setProgress { target -> change(target.toDouble()); true }
            }
            .focusable(interactionSource = source),
    ) {
        repeat(5) { i ->
            val fill = ((shown ?: 0.0) / 2 - i).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.size(44.dp)) {
                Icon(CvIcons.Star, null, Modifier.fillMaxSize(), tint = Color.White.copy(alpha = 0.35f))
                if (fill > 0f) Icon(CvIcons.StarFilled, null, Modifier.fillMaxSize().drawWithContent {
                    clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                }, tint = Tokens.Colors.Gold)
            }
        }
    }
}

/** Half-star segment under pointer [x] of a row [width] wide: 20 segments → 0.5..10. */
private fun ratingAt(x: Float, width: Float) = (ceil(x / width * 20) / 2.0).coerceIn(MIN_RATING, MAX_RATING)

/** §7.11 word label (tvApp's table; that helper is not shared with composeApp). */
private fun ratingWord(value: Double) = when {
    value <= 3.0 -> "Poor"
    value <= 5.0 -> "Meh"
    value <= 7.0 -> "Good"
    value <= 8.5 -> "Great"
    else -> "Masterpiece"
}

private const val MIN_RATING = 0.5
private const val MAX_RATING = 10.0
