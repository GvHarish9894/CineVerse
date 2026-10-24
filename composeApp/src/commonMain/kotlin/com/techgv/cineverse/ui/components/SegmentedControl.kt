package com.techgv.cineverse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.LocalShellStyle
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.theme.LocalReduceMotion
import com.techgv.cineverse.ui.theme.bodyStyle

/**
 * Equal-width segments with an accent pill that slides with an overshooting spring
 * (`.seg-pill`: left .32s cubic-bezier(.3,1.3,.5,1)); a snap under reduce motion. Mobile: radius 14/10, 34dp
 * segments; web: fully rounded, 32dp. Each segment is a Tab with `selected` semantics; changes tick.
 * Needs a bounded width (fillMaxWidth or an explicit width, e.g. 176dp for the compact row-header variant).
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    require(options.isNotEmpty()) { "SegmentedControl needs at least one option" }
    val web = LocalShellStyle.current == ShellStyle.Web
    val outer = RoundedCornerShape(if (web) Tokens.Radius.Chip else 14.dp)
    val inner = RoundedCornerShape(if (web) Tokens.Radius.Chip else 10.dp)
    val segmentHeight = when {
        compact -> 28.dp
        web -> 32.dp
        else -> 34.dp
    }
    val reduceMotion = LocalReduceMotion.current
    // Mobile: each option is a ≥ 48dp touch target; the drawn track stays segment + 2 × 4dp, centred in it.
    val optionMinHeight = if (web) segmentHeight else maxOf(MinTouchTarget, segmentHeight + 8.dp)
    val trackInset = if (web) 0.dp else (optionMinHeight - segmentHeight - 8.dp) / 2
    // Mobile options span the whole target, so their label padding also covers the track inset + track padding.
    val labelPaddingV = if (web) 4.dp else 4.dp + trackInset + 4.dp
    BoxWithConstraints(modifier) {
        val segmentWidth = (maxWidth - 8.dp) / options.size
        val pillOffset by animateDpAsState(
            segmentWidth * selectedIndex,
            if (reduceMotion) snap() else spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
            label = "segmentPill",
        )
        Box(Modifier.matchParentSize().padding(vertical = trackInset).clip(outer).background(Color.White.copy(alpha = 0.08f)).padding(4.dp)) {
            Box(Modifier.offset(x = pillOffset).width(segmentWidth).fillMaxHeight().clip(inner).background(Tokens.Colors.Accent))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = if (web) 4.dp else 0.dp).selectableGroup()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color by animateColorAsState(if (selected) Tokens.Colors.OnAccent else Tokens.Colors.TextPrimary.copy(alpha = 0.82f), label = "segmentText")
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = optionMinHeight)
                        .cvClickable({ if (!selected) onSelect(index) }, role = Role.Tab, haptic = HapticKind.Tick, focusShape = inner)
                        .semantics { this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = bodyStyle(if (compact) 12.sp else if (web) 13.5.sp else 13.sp, FontWeight.SemiBold),
                        color = color,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = labelPaddingV),
                    )
                }
            }
        }
    }
}
