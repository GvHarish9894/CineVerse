package com.techgv.cineverse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.math.roundToInt

/** `vote_average × 10`, rounded (§6). */
fun scorePercent(voteAverage: Double): Int = (voteAverage * 10).roundToInt().coerceIn(0, 100)

/** Green ≥ 70, yellow ≥ 40, else red (§6). */
fun scoreColor(percent: Int): Color = when {
    percent >= 70 -> Color(0xFF21D07A)
    percent >= 40 -> Color(0xFFD2D531)
    else -> Color(0xFFDB2360)
}

/** 48 dp user-score ring; renders nothing when [voteCount] is 0 (§6). */
@Composable
fun ScoreRing(voteAverage: Double, voteCount: Int, modifier: Modifier = Modifier) {
    if (voteCount == 0) return
    val percent = scorePercent(voteAverage)
    val color = scoreColor(percent)
    Box(modifier.size(48.dp).semantics { contentDescription = "User score $percent percent" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            drawCircle(Color(0xFF0B0A16))
            val inset = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color(0x24FFFFFF), 0f, 360f, false, inset, arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * percent / 100f, false, inset, arcSize, style = Stroke(stroke))
        }
        Text("$percent%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
