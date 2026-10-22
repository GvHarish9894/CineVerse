package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** One key of the §7.7 keyboard; [id] is its stable focus key. */
sealed class KeyboardKey(val id: String, val label: String, val wide: Boolean = false) {
    class Char(val char: kotlin.Char) : KeyboardKey("k$char", char.uppercase())
    data object Space : KeyboardKey("kspace", "Space", wide = true)
    data object Delete : KeyboardKey("kdel", "⌫")
    data object Clear : KeyboardKey("kclear", "Clear", wide = true)
}

/** Six columns A–Z, 0–9, then Space / Delete / Clear (prototype `KB`). */
val KEYBOARD_ROWS: List<List<KeyboardKey>> =
    listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890").map { row -> row.map { KeyboardKey.Char(it) } } +
        listOf(listOf(KeyboardKey.Space, KeyboardKey.Delete, KeyboardKey.Clear))

/**
 * The Search keyboard (§7.7). [keyModifier] receives each key's id and flat index (for focus memory);
 * [onKey] gets the pressed key.
 */
@Composable
fun OnScreenKeyboard(
    onKey: (KeyboardKey) -> Unit,
    modifier: Modifier = Modifier,
    keyModifier: (id: String, index: Int) -> Modifier = { _, _ -> Modifier },
) {
    var index = 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KEYBOARD_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    KeyCap(key, onKey, keyModifier(key.id, index++))
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: KeyboardKey, onKey: (KeyboardKey) -> Unit, modifier: Modifier) {
    val width = if (key.wide) 112.dp else 52.dp
    val description = when (key) {
        is KeyboardKey.Char -> key.char.toString()
        KeyboardKey.Delete -> "Delete"
        else -> key.label
    }
    FocusSurface(
        onClick = { onKey(key) },
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.08f,
        modifier = modifier.semantics { contentDescription = description }.size(width, 46.dp),
    ) { focused ->
        Box(
            Modifier.background(if (focused) Color.White else UiColors.Tile).size(width, 46.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                key.label,
                color = if (focused) UiColors.Background else Color.White,
                fontSize = if (key.wide) 14.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
