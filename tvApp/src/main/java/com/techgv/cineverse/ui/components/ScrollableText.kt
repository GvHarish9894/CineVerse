package com.techgv.cineverse.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.techgv.cineverse.ui.feedback.LocalFeedback
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Long plain text (biography, episode overview, review) readable by D-pad alone: one focusable region that
 * scrolls a page per DOWN / UP while there is more text that way; at the end the key is not consumed, so focus
 * moves on to the next / previous control (e.g. Close). [modifier] must bound its height.
 */
@Composable
fun ScrollableText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalFeedback.current.reduceMotion
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // One page, minus a line of overlap so the reader keeps their place.
                val page = (scroll.viewportSize * PAGE_FRACTION).roundToInt()
                val target = when (event.key) {
                    Key.DirectionDown -> if (scroll.value < scroll.maxValue) (scroll.value + page).coerceAtMost(scroll.maxValue) else null
                    Key.DirectionUp -> if (scroll.value > 0) (scroll.value - page).coerceAtLeast(0) else null
                    else -> null
                } ?: return@onKeyEvent false
                scope.launch { if (reduceMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target) }
                true
            }
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .focusable()
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        Text(text, style = style, color = color)
    }
}

private const val PAGE_FRACTION = 0.85f
