package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

val PillShape = RoundedCornerShape(percent = 50)

/** 44 dp tab / filter chip (§6). [onFocused] lets tabs switch on focus (§7.3). */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    FocusSurface(
        onClick = onClick,
        shape = PillShape,
        focusedScale = 1.06f,
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { this.selected = selected }
            .height(44.dp),
    ) { focused ->
        val background = when {
            focused -> Color.White
            selected -> UiColors.Accent
            else -> UiColors.Tile
        }
        Box(Modifier.background(background).height(44.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (focused) UiColors.Background else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
