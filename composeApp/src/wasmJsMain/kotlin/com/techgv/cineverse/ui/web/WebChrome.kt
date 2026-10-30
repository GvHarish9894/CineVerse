package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.ArtPalette
import com.techgv.cineverse.ui.components.CvIcons
import com.techgv.cineverse.ui.components.PosterCardSkeleton
import com.techgv.cineverse.ui.components.SkeletonLines
import com.techgv.cineverse.ui.components.contentGutter
import com.techgv.cineverse.ui.components.cvClickable
import com.techgv.cineverse.ui.components.disabledAlpha
import com.techgv.cineverse.ui.components.radialEllipse
import com.techgv.cineverse.ui.components.shimmer
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.LocalAmbientGlowEnabled
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle

/**
 * The kit's `.wrap` column: content inset by the web gutter (32dp, 16dp ≤ 640) inside a centred 1320dp max width,
 * i.e. `max(gutter, (width - 1320) / 2 + gutter)` on each side (92dp at 1440). Unbounded widths get no inset.
 */
fun Modifier.webGutter(): Modifier = layout { measurable, constraints ->
    val gutter = if (constraints.hasBoundedWidth) contentGutter(ShellStyle.Web, constraints.maxWidth.toDp()).roundToPx() else 0
    val inner = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth - 2 * gutter).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - 2 * gutter).coerceAtLeast(0) else constraints.maxWidth,
        ),
    )
    layout(inner.width + 2 * gutter, inner.height) { inner.place(gutter, 0) }
}

/** A page section in the [webGutter] column (use one per LazyColumn item). */
@Composable
fun WebPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().webGutter(), content = content)
}

/** `.phead` + `.page-t` (48sp display, 36sp ≤ 640) and the optional `.page-s` subtitle. Place it inside [WebPage]. */
@Composable
fun WebPageTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val phone = maxWidth <= 640.dp
        Column(Modifier.padding(top = 44.dp)) {
            Text(
                title,
                style = displayStyle(if (phone) 36.sp else Tokens.Typography.ScaleWeb.PageTitle, trackingEm = -0.03f).copy(lineHeight = 1.05.em),
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, Modifier.padding(top = 8.dp), style = bodyStyle(16.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * The kit's search box without a floating label: search icon, placeholder while empty, translucent fill. Header size
 * (`.hsearch`, 44dp, radius 12) or [large] (`.sbig`, 60dp, radius 18). Give it a width. [modifier] lands on the
 * editable node (tags, focus requesters). External [query] changes replace the text; same-text updates keep the
 * selection and IME composition. Disabled: edits and IME submits are ignored.
 */
@Composable
fun WebSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search movies, TV and people",
    invalid: Boolean = false,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    var editor by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    LaunchedEffect(query) { if (editor.text != query) editor = TextFieldValue(query, TextRange(query.length)) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (large) 18.dp else 12.dp)
    val stroke = when {
        invalid -> Tokens.Colors.Danger
        focused -> Color.White.copy(alpha = 0.55f)
        else -> Tokens.Colors.Stroke
    }
    val textStyle = bodyStyle(if (large) 19.sp else 14.5.sp).copy(color = Tokens.Colors.TextPrimary)
    BasicTextField(
        editor,
        { if (enabled) { editor = it; onQueryChange(it.text) } },
        modifier
            .semantics { contentDescription = placeholder }
            .disabledAlpha(enabled)
            .heightIn(min = if (large) 60.dp else 44.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (large) 0.07f else 0.08f))
            .border(1.dp, stroke, shape),
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(Tokens.Colors.TextPrimary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (enabled) onSubmit() }),
        interactionSource = source,
        decorationBox = { field ->
            Row(
                Modifier.padding(horizontal = if (large) 20.dp else 14.dp),
                horizontalArrangement = Arrangement.spacedBy(if (large) 12.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CvIcons.Search, contentDescription = null, tint = Tokens.Colors.TextPrimary.copy(alpha = 0.65f), modifier = Modifier.size(if (large) 26.dp else 20.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (editor.text.isEmpty()) {
                        Text(placeholder, style = textStyle, color = Tokens.Colors.TextPrimary.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    field()
                }
            }
        },
    )
}

/**
 * Page-level ambient glow (tokens `glow.ambient`, web): two radial gradients of [palette] (c1 at 72%/22%, c2 at
 * 14%/60% of a 120%-wide, 1100dp-tall area starting 120dp above the page), opacity 0.45 × [strength], behind every
 * page; off with the Ambient glow setting. Rasterised only when the size, palette or strength changes into an opaque
 * 1/8-scale bitmap on the page background, then stretched (bilinear): redrawing full-window translucent gradients every
 * frame is too costly on CPU-rasterised canvases, and a full-resolution cache costs ~22 MB at DPR 2. Both gradients fade
 * out above its 980dp height, so its bottom edge is seamless.
 */
@Composable
fun WebAmbientGlow(
    modifier: Modifier = Modifier,
    palette: ArtPalette = ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary),
    strength: Float = Tokens.Glow.AmbientDefaultStrength,
) {
    if (!LocalAmbientGlowEnabled.current) return
    val opacity = Tokens.Glow.AmbientOpacityWeb * strength
    Spacer(modifier.fillMaxWidth().height(980.dp).testTag("web-ambient-glow").drawWithCache {
        val scale = 1f / 8f
        val bitmap = ImageBitmap((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
        val small = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
        CanvasDrawScope().draw(this, layoutDirection, Canvas(bitmap), small) {
            // Opaque (pre-composited on the page background): the per-frame stretch is a plain copy, no blending.
            drawRect(Tokens.Colors.Background)
            val left = -0.10f * small.width
            val width = 1.2f * small.width
            val top = -120.dp.toPx() * scale
            val height = 1100.dp.toPx() * scale
            fun at(x: Float, y: Float) = Offset(left + width * x, top + height * y)
            radialEllipse(at(0.72f, 0.22f), width * 0.5f, height * 0.5f, palette.c1.copy(alpha = palette.c1.alpha * opacity), 0.70f)
            radialEllipse(at(0.14f, 0.60f), width * 0.45f, height * 0.45f, palette.c2.copy(alpha = palette.c2.alpha * opacity), 0.72f)
        }
        onDrawBehind { drawImage(bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low) }
    })
}

/** `.backlink`: chevron icon + label ("Back to Movies"), muted until hovered; a link, not a pill. */
@Composable
fun WebBackLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val color = if (hovered) Color.White else Tokens.Colors.TextPrimary.copy(alpha = 0.8f)
    Row(
        modifier.heightIn(min = 48.dp).cvClickable(onClick, focusShape = RoundedCornerShape(8.dp), pressedScale = 1f, interactionSource = source),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CvIcons.ChevronLeft, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(label, style = bodyStyle(14.sp, FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/**
 * Whole-page loading placeholder in the house Skeleton style: a hero block (kicker, title, meta, overview, buttons)
 * and two poster rows. [label] is announced politely (the visual has no text).
 */
@Composable
fun WebLoadingSkeleton(label: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = label; liveRegion = LiveRegionMode.Polite }) {
        WebHeroSkeleton(Modifier.padding(vertical = 96.dp))
        repeat(2) { WebRowSkeleton(Modifier.padding(top = 44.dp)) }
    }
}

/** The hero's loading shape (kicker, title, meta line, overview, three pill buttons) in the gutter column. */
@Composable
fun WebHeroSkeleton(modifier: Modifier = Modifier) {
    WebPage(modifier) {
        Column(Modifier.widthIn(max = 660.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(220.dp).height(14.dp).shimmer())
            Box(Modifier.fillMaxWidth(0.8f).height(64.dp).shimmer(RoundedCornerShape(14.dp)))
            Box(Modifier.width(300.dp).height(16.dp).shimmer())
            SkeletonLines(2, Modifier.widthIn(max = 580.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(150.dp, 180.dp, 130.dp).forEach { Box(Modifier.width(it).height(46.dp).shimmer(RoundedCornerShape(Tokens.Radius.Button))) }
            }
        }
    }
}

@Composable
private fun WebRowSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        WebPage { Box(Modifier.width(200.dp).height(26.dp).shimmer()) }
        LazyRow(Modifier.fillMaxWidth().padding(top = 24.dp).webGutter(), horizontalArrangement = Arrangement.spacedBy(20.dp), userScrollEnabled = false) {
            items(6) { PosterCardSkeleton() }
        }
    }
}
