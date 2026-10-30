package com.techgv.cineverse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.LocalShellStyle
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.haptics.LocalHaptics
import com.techgv.cineverse.ui.theme.LocalReduceMotion
import com.techgv.cineverse.ui.theme.bodyStyle

/** What sits at the end of a [SettingRow]. */
sealed interface SettingTrailing {
    /** Switch; the whole row toggles (Role.Switch). */
    data class Toggle(val checked: Boolean, val onCheckedChange: (Boolean) -> Unit) : SettingTrailing

    /** Current value + "›"; the row opens a picker / cycles. */
    data class Value(val value: String, val onClick: () -> Unit) : SettingTrailing
}

/**
 * Profile/Settings row (`.setrow`): tinted icon tile, title + description, switch or value. Mobile 58dp min height
 * with 16dp side padding (inside a [SettingsGroup]); web 62dp. [showDivider] draws the top hairline.
 */
@Composable
fun SettingRow(
    title: String,
    description: String?,
    icon: ImageVector,
    iconTint: Color,
    trailing: SettingTrailing,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val haptics = LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val interaction = when (trailing) {
        is SettingTrailing.Toggle -> Modifier
            .focusRing(source, shape)
            .toggleable(trailing.checked, source, indication = null, role = Role.Switch) { on ->
                haptics.perform(if (on) HapticKind.ToggleOn else HapticKind.ToggleOff)
                trailing.onCheckedChange(on)
            }
        is SettingTrailing.Value -> Modifier.cvClickable(trailing.onClick, haptic = HapticKind.Select, focusShape = shape, pressedScale = 1f, interactionSource = source)
    }
    Column(modifier.fillMaxWidth()) {
        if (showDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = if (web) 0.07f else 0.06f)))
        Row(
            interaction.heightIn(min = if (web) 62.dp else 58.dp).padding(horizontal = if (web) 0.dp else 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(if (web) 38.dp else 34.dp).clip(RoundedCornerShape(if (web) 12.dp else 10.dp)).background(iconTint.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = bodyStyle(if (web) 15.5.sp else 15.sp, FontWeight.SemiBold))
                if (description != null) {
                    Text(description, style = bodyStyle(if (web) 13.sp else 12.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp))
                }
            }
            when (trailing) {
                is SettingTrailing.Toggle -> CvSwitch(trailing.checked)
                is SettingTrailing.Value -> Text("${trailing.value}  ›", style = bodyStyle(13.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

/** `.sw` 50×30 track (accent when on) with a 24dp knob on a spring (`.kn` left .24s cubic-bezier(.3,1.4,.5,1)). */
@Composable
fun CvSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val knob by animateDpAsState(if (checked) 23.dp else 3.dp, if (reduceMotion) snap() else spring(dampingRatio = 0.6f), label = "knob")
    val track by animateColorAsState(if (checked) Tokens.Colors.Accent else Color.White.copy(alpha = 0.2f), label = "track")
    Box(modifier.width(50.dp).height(30.dp).clip(RoundedCornerShape(Tokens.Radius.Chip)).background(track)) {
        Box(
            Modifier.offset(x = knob, y = 3.dp).size(24.dp)
                .dropShadow(CircleShape, Shadow(6.dp, Color.Black.copy(alpha = 0.35f), offset = DpOffset(0.dp, 2.dp)))
                .clip(CircleShape).background(Color.White),
        )
    }
}

/** Mobile `.group`: rounded white-6% container for [SettingRow]s (pass showDivider for all rows but the first). */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Tokens.Colors.Tile)) { content() }
}
