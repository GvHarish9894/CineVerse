package com.techgv.cineverse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
import kotlin.math.roundToInt

/** `vote_average × 10`, rounded, 0–100 (TV spec §6). */
fun scorePercent(voteAverage: Double): Int = (voteAverage * 10).roundToInt().coerceIn(0, 100)

/** Same thresholds as TV: green ≥ 70, yellow ≥ 40, else red. */
fun scoreColor(percent: Int): Color = when {
    percent >= 70 -> Tokens.Colors.ScoreHigh
    percent >= 40 -> Tokens.Colors.ScoreMid
    else -> Tokens.Colors.ScoreLow
}

/** Ring sizes from the prototypes: outer, inner disc, number size. */
enum class ScoreRingSize(val outer: Dp, val inner: Dp, val text: TextUnit) {
    /** Web poster card, overlapping the art's bottom-left edge. */
    Mini(40.dp, 34.dp, 11.5.sp),
    /** Mobile detail header. */
    Mobile(44.dp, 36.dp, 12.sp),
    /** Web hero meta line. */
    Web(52.dp, 44.dp, 14.sp),
    /** Web detail hero. */
    Large(72.dp, 62.dp, 20.sp),
}

/** User-score ring; renders nothing when [voteCount] is 0 (TV spec §6: no score without votes). */
@Composable
fun ScoreRing(voteAverage: Double, voteCount: Int, modifier: Modifier = Modifier, size: ScoreRingSize = ScoreRingSize.Mobile) {
    if (voteCount == 0) return
    val percent = scorePercent(voteAverage)
    val color = scoreColor(percent)
    Box(
        modifier.size(size.outer).clearAndSetSemantics { contentDescription = "User score $percent percent" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (size.outer - size.inner).toPx() / 2
            drawCircle(Tokens.Colors.Surface)
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.14f), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            drawArc(color, -90f, 3.6f * percent, false, topLeft, arc, style = Stroke(stroke))
        }
        Row(verticalAlignment = Alignment.Top) {
            Text("$percent", style = bodyStyle(size.text, FontWeight.Bold), color = Color.White, maxLines = 1)
            Text("%", style = bodyStyle(size.text * 0.6f, FontWeight.Bold), color = Color.White, maxLines = 1)
        }
    }
}
