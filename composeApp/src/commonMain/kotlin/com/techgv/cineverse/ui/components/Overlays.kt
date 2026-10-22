package com.techgv.cineverse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.techgv.cineverse.ui.adaptive.LocalShellStyle
import com.techgv.cineverse.ui.adaptive.LocalWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.adaptive.WindowClass
import com.techgv.cineverse.ui.design.Scrims
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.theme.LocalAmbientGlowEnabled
import com.techgv.cineverse.ui.theme.LocalReduceMotion
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle
import kotlinx.coroutines.delay

/**
 * Ambient glow behind content (not scrolling with it): two radial gradients of the featured title's palette.
 * Mobile: c1 at 70%/30%, c2 at 20%/70% over the top 68% (bleeding 35% sideways), opacity 0.60 × strength,
 * 900 ms colour crossfade. Web: c1 at 72%/22%, c2 at 14%/60% over the top 1100dp, 0.45 × strength, 1 s.
 * The gradients already fade to transparent, so no blur pass is needed (same look on Android < 12).
 */
@Composable
fun AmbientGlow(palette: ArtPalette, modifier: Modifier = Modifier, strength: Float = Tokens.Glow.AmbientDefaultStrength) {
    if (!LocalAmbientGlowEnabled.current) return
    val web = LocalShellStyle.current == ShellStyle.Web
    val fade = tween<Color>(if (web) 1000 else 900)
    val c1 by animateColorAsState(palette.c1, fade, label = "glowC1")
    val c2 by animateColorAsState(palette.c2, fade, label = "glowC2")
    val webTop = with(LocalDensity.current) { (-120).dp.toPx() }
    val webHeight = with(LocalDensity.current) { 1100.dp.toPx() }
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = (if (web) Tokens.Glow.AmbientOpacityWeb else Tokens.Glow.AmbientOpacityMobile) * strength }) {
        val left = if (web) -0.10f * size.width else -0.35f * size.width
        val width = if (web) 1.2f * size.width else 1.7f * size.width
        val top = if (web) webTop else -0.12f * size.height
        val height = if (web) webHeight else 0.68f * size.height
        fun at(x: Float, y: Float) = Offset(left + width * x, top + height * y)
        if (web) {
            radialEllipse(at(0.72f, 0.22f), width * 0.5f, height * 0.5f, c1, 0.70f)
            radialEllipse(at(0.14f, 0.60f), width * 0.45f, height * 0.45f, c2, 0.72f)
        } else {
            radialEllipse(at(0.70f, 0.30f), width * 0.5f, height * 0.5f, c1, 0.70f)
            radialEllipse(at(0.20f, 0.70f), width * 0.45f, height * 0.45f, c2, 0.72f)
        }
    }
}

/**
 * Bottom sheet (`.sheet`): raised surface, 30dp top radius, 40×5 handle, scrim rgba(5,4,12,.62), drag-to-dismiss and
 * scrim tap close (Material 3 ModalBottomSheet underneath).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CvBottomSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = Tokens.Radius.Sheet, topEnd = Tokens.Radius.Sheet),
        containerColor = Tokens.Colors.SurfaceSheet,
        contentColor = Tokens.Colors.TextPrimary,
        scrimColor = Scrims.SheetScrim,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 18.dp).size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f)))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 38.dp), content = content)
    }
}

/** Sheet/dialog kicker + title (`.sh-k`/`.sh-t`, `.dlg-k`/`.dlg-t`). */
@Composable
fun OverlayHeading(kicker: String?, title: String) {
    val web = LocalShellStyle.current == ShellStyle.Web
    if (kicker != null) {
        Text(kicker.uppercase(), style = bodyStyle(if (web) 12.sp else 11.5.sp, FontWeight.Bold, trackingEm = 0.12f), color = Tokens.Colors.TextPrimary.copy(alpha = 0.64f))
    }
    Text(title, style = displayStyle(if (web) 30.sp else 26.sp), modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

@Immutable
data class ToastMessage(val text: String, val subtitle: String? = null, val icon: ImageVector = CvIcons.Check)

/** One toast at a time; a new [show] replaces the current one and restarts its timer. */
@Stable
class ToastState {
    var current by mutableStateOf<ToastMessage?>(null)
        private set
    internal var serial by mutableIntStateOf(0)
        private set

    fun show(message: ToastMessage) {
        current = message
        serial++
    }

    fun dismiss() {
        current = null
    }
}

/** Provided by the shell's [ToastHost]. */
val LocalToasts = staticCompositionLocalOf<ToastState> { error("No ToastHost: wrap content in MobileShell/WebShell") }

/**
 * Snackbar/toast (`.toast`): mobile = full width above the floating tab bar (bottom 100dp), 2.6 s; web = bottom-left
 * 24dp, 2.8 s. Fade + 16dp rise; announced politely to screen readers.
 */
@Composable
fun BoxScope.ToastHost(state: ToastState) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val message = state.current
    LaunchedEffect(state.serial) {
        if (message != null) {
            delay(if (web) 2800 else 2600)
            state.dismiss()
        }
    }
    val rise = if (LocalReduceMotion.current) 0 else with(LocalDensity.current) { 16.dp.roundToPx() }
    var last by remember { mutableStateOf(message) }
    if (message != null) last = message
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(300)) + slideInVertically(tween(350, easing = EmphasizedEasing)) { rise },
        exit = fadeOut(tween(300)) + slideOutVertically(tween(350)) { rise },
        modifier = Modifier.align(if (web) Alignment.BottomStart else Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = if (web) 24.dp else 16.dp, end = if (web) 24.dp else 16.dp, bottom = if (web) 24.dp else 100.dp),
    ) {
        val shown = last ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(18.dp)
        Row(
            (if (web) Modifier.widthIn(max = 480.dp) else Modifier.fillMaxWidth())
                .dropShadow(shape, Shadow(50.dp, Color.Black.copy(alpha = 0.5f), offset = DpOffset(0.dp, 20.dp)))
                .clip(shape).background(Color(30, 27, 54).copy(alpha = if (web) 0.97f else 0.96f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .padding(start = 14.dp, top = if (web) 14.dp else 12.dp, bottom = if (web) 14.dp else 12.dp, end = if (web) 18.dp else 14.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(if (web) 36.dp else 34.dp).clip(RoundedCornerShape(11.dp)).background(Tokens.Colors.Accent), contentAlignment = Alignment.Center) {
                Icon(shown.icon, contentDescription = null, tint = Tokens.Colors.OnAccent, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(shown.text, style = bodyStyle(if (web) 14.5.sp else 14.sp, FontWeight.SemiBold))
                if (shown.subtitle != null) {
                    Text(shown.subtitle, style = bodyStyle(if (web) 12.5.sp else 12.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.68f))
                }
            }
        }
    }
}

/** Platform dialog properties: full-width content; web/iOS draw the kit's 72% scrim, Android uses the window dim. */
expect fun cvDialogProperties(): DialogProperties

/**
 * Modal dialog (`.dialog`): ≤ 560dp, radius 28, raised surface, close button, kicker + title + [content].
 * A platform dialog window, so focus is trapped inside (Tab cycles within) and screen readers treat it as modal.
 * Esc / back / scrim tap dismiss. On close, focus returns to [restoreFocus] (attach it to the trigger).
 * Enter: 350 ms rise + scale .97 → 1 (fade only under reduce motion).
 */
@Composable
fun CvDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    restoreFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val narrow = LocalWindowClass.current == WindowClass.Phone
    val reduceMotion = LocalReduceMotion.current
    DisposableEffect(restoreFocus) { onDispose { restoreFocus?.let { runCatching { it.requestFocus() } } } }
    Dialog(onDismissRequest = onDismissRequest, properties = cvDialogProperties()) {
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) { enter.animateTo(1f, tween(350, easing = EmphasizedEasing)) }
        val closeFocus = remember { FocusRequester() }
        // Keyboard users land inside the dialog; touch users get no focus ring.
        if (web) LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
        val shape = RoundedCornerShape(Tokens.Radius.Dialog)
        Column(
            modifier
                .padding(20.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onDismissRequest() }
                }
                .graphicsLayer {
                    alpha = enter.value
                    if (!reduceMotion) {
                        translationY = (1f - enter.value) * 20.dp.toPx()
                        val scale = 0.97f + 0.03f * enter.value
                        scaleX = scale
                        scaleY = scale
                    }
                }
                .dropShadow(shape, Shadow(120.dp, Color.Black.copy(alpha = 0.6f), offset = DpOffset(0.dp, 40.dp)))
                .clip(shape)
                .background(Tokens.Colors.SurfaceRaised)
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .padding(horizontal = if (narrow) 22.dp else 36.dp, vertical = if (narrow) 28.dp else 34.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { OverlayHeading(kicker, title) }
                RoundIconButton(
                    CvIcons.Close, "Close", onDismissRequest, Modifier.focusRequester(closeFocus),
                    diameter = 38.dp, container = Color.White.copy(alpha = 0.08f), haptic = HapticKind.Back,
                )
            }
            content()
        }
    }
}
