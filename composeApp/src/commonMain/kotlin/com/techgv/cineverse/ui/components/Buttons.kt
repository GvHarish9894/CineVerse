package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.LocalShellStyle
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.theme.bodyStyle

/** Button fills from the prototypes. */
enum class ButtonVariant(val container: Color, val content: Color) {
    /** Primary: accent with dark text (white fails contrast on accent) and an accent glow. */
    Accent(Tokens.Colors.Accent, Tokens.Colors.OnAccent),
    /** Over art: white 14% (mobile hero). */
    Glass(Color.White.copy(alpha = 0.14f), Color.White),
    /** Secondary: white 10% (web `.btn.ghost`, player controls). */
    Ghost(Color.White.copy(alpha = 0.10f), Color.White),
    /** "Sign in with TMDB" on the profile card. */
    White(Color.White, Tokens.Colors.OnFocus),
}

/** Web hover brightens fills by 8% (`filter: brightness(1.08)`), approximated by mixing toward white. */
private fun Color.hovered(on: Boolean) = if (on) Color(red + (1 - red) * 0.08f, green + (1 - green) * 0.08f, blue + (1 - blue) * 0.08f, alpha) else this

/** `.btn`: 46dp pill (38dp `small`, web), optional leading icon. Min touch target 48dp via M3's interactive size. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Accent,
    icon: ImageVector? = null,
    small: Boolean = false,
    haptic: HapticKind = HapticKind.Select,
    enabled: Boolean = true,
) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.Radius.Button)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .disabledAlpha(enabled)
            .then(if (variant == ButtonVariant.Accent && enabled) Modifier.dropShadow(shape, Shadow(30.dp, Tokens.Colors.Accent, (-10).dp, DpOffset(0.dp, 12.dp))) else Modifier)
            .cvClickable(onClick, haptic = haptic, focusShape = shape, pressedScale = if (web) 0.97f else Tokens.Motion.PressScale, enabled = enabled, interactionSource = source)
            .clip(shape)
            .background(variant.container.hovered(hovered))
            .heightIn(min = if (small) 38.dp else 46.dp)
            .padding(horizontal = if (small) 16.dp else if (web) 22.dp else 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = variant.content, modifier = Modifier.size(if (web) 20.dp else 22.dp))
        Text(text, style = bodyStyle(if (small) 14.sp else 15.sp, FontWeight.SemiBold), color = variant.content, maxLines = 1)
    }
}

/**
 * Round icon button: mobile `.glassbtn` 40dp over art, `.btn.round` 46dp, web `.circ` 50dp / `.arrow` 40dp.
 * Visual size [diameter]; the touch target is always ≥ 48dp. Web: hovering shows [contentDescription] as a tooltip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 40.dp,
    container: Color = Color(12, 10, 24).copy(alpha = 0.55f),
    content: Color = Color.White,
    haptic: HapticKind = HapticKind.Tap,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val button = @Composable {
        Box(
            modifier
                .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                .cvClickable(onClick, haptic = haptic, focusShape = CircleShape, enabled = enabled, interactionSource = source)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(diameter).disabledAlpha(enabled).clip(CircleShape).background(container.hovered(hovered)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (LocalShellStyle.current == ShellStyle.Web) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            // `.tip`: white, dark text, radius 8, 12.5 semibold.
            tooltip = {
                PlainTooltip(shape = RoundedCornerShape(8.dp), containerColor = Color.White, contentColor = Tokens.Colors.OnFocus) {
                    Text(contentDescription, style = bodyStyle(12.5.sp, FontWeight.SemiBold))
                }
            },
            state = rememberTooltipState(),
            content = button,
        )
    } else {
        button()
    }
}

/** Detail `.act` tile (66dp): Watchlist (accent when on), Rate (gold when rated), Trailer. */
@Composable
fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color.White.copy(alpha = 0.08f),
    content: Color = Tokens.Colors.TextPrimary,
    haptic: HapticKind = HapticKind.Select,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .disabledAlpha(enabled)
            .cvClickable(onClick, haptic = haptic, focusShape = shape, enabled = enabled)
            .clip(shape)
            .background(container)
            .heightIn(min = 66.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Text(label, style = bodyStyle(12.5.sp, FontWeight.SemiBold), color = content, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * Filter/list chip (`.chip`): selected = white with dark text (mobile) / accent (web list chips pass [selectedColor]).
 * 36dp mobile, 38dp web; semantics expose selection.
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selectedColor: Color = Color.White,
    selectedContent: Color = Tokens.Colors.OnFocus,
) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val shape = RoundedCornerShape(Tokens.Radius.Chip)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val content = if (selected) selectedContent else Tokens.Colors.TextPrimary
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .cvClickable(onClick, role = Role.Tab, haptic = HapticKind.Select, focusShape = shape, interactionSource = source)
            .semantics { this.selected = selected }
            .clip(shape)
            .background((if (selected) selectedColor else Color.White.copy(alpha = 0.08f)).hovered(hovered))
            .sizeIn(minHeight = if (web) 38.dp else 36.dp)
            .padding(horizontal = if (web) 16.dp else 15.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(label, style = bodyStyle(if (web) 14.sp else 13.5.sp, FontWeight.SemiBold), color = content, maxLines = 1)
    }
}

/** Non-interactive meta chip (`.mchip`: genres on the hero, person facts). */
@Composable
fun MetaChip(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = bodyStyle(12.sp, FontWeight.SemiBold),
        color = Tokens.Colors.TextPrimary.copy(alpha = 0.92f),
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f))
            .heightIn(min = 26.dp).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
