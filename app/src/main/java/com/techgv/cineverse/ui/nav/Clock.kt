package com.techgv.cineverse.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private const val CLOCK_TICK_MILLIS = 15_000L

/** §5 top-right clock, `HH:mm`, refreshed every 15 s. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now().format(clockFormat)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now().format(clockFormat)
            delay(CLOCK_TICK_MILLIS)
        }
    }
    Text(now, modifier = modifier, color = Color(0xE6F4F2FF), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}
