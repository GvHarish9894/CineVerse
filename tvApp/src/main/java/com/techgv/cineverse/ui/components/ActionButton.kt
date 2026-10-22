package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** §6 ActionButton variants: [Solid] = main call to action, [On] = an active toggle (e.g. on watchlist). */
enum class ActionButtonStyle { Default, Primary, Solid, On }

/** Icon glyphs used by action buttons (no icon font in the app). */
object ActionIcon {
    const val PLAY = "▶"
    const val PLUS = "+"
    const val CHECK = "✓"
    const val INFO = "ⓘ"
    const val USER = "◉"
    const val HEART = "♥"
    const val STAR = "★"
}

/**
 * 56 dp pill button: [icon] + [label] (§6). A disabled button stays focusable but ignores OK (e.g.
 * Play trailer while its videos load, §7.2).
 */
@Composable
fun ActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ActionButtonStyle = ActionButtonStyle.Default,
    enabled: Boolean = true,
) {
    FocusSurface(
        onClick = onClick,
        shape = PillShape,
        focusedScale = 1.06f,
        enabled = enabled,
        glowColor = UiColors.Accent,
        modifier = modifier.semantics { contentDescription = label }.height(56.dp),
    ) { focused ->
        val (background, content) = when {
            focused -> Color.White to UiColors.Background
            style == ActionButtonStyle.Solid -> UiColors.Accent to Color.White
            style == ActionButtonStyle.On -> Color(0x33FF3D7F) to Color.White
            style == ActionButtonStyle.Primary -> Color(0x33FFFFFF) to Color.White
            else -> UiColors.Tile to Color.White
        }
        Row(
            Modifier.background(background).height(56.dp).padding(horizontal = 24.dp).alpha(if (enabled) 1f else 0.5f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon, color = content, fontSize = 18.sp)
            Text(label, color = content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
