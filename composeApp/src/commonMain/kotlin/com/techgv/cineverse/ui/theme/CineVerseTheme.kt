package com.techgv.cineverse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.LocalShellStyle
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticStrength
import com.techgv.cineverse.ui.haptics.LocalHaptics
import com.techgv.cineverse.ui.haptics.rememberPlatformHaptics
import com.techgv.cineverse.ui.resources.Res
import com.techgv.cineverse.ui.resources.bricolage_grotesque_bold
import com.techgv.cineverse.ui.resources.bricolage_grotesque_extrabold
import com.techgv.cineverse.ui.resources.bricolage_grotesque_medium
import com.techgv.cineverse.ui.resources.inter_bold
import com.techgv.cineverse.ui.resources.inter_medium
import com.techgv.cineverse.ui.resources.inter_regular
import com.techgv.cineverse.ui.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/** Bricolage Grotesque (display: titles, row headers, big numbers) + Inter (body), bundled (OFL). */
@Immutable
class CvFonts(val display: FontFamily, val body: FontFamily)

val LocalCvFonts = staticCompositionLocalOf { CvFonts(FontFamily.Default, FontFamily.Default) }

/** OS reduce-motion OR the app's Reduce motion setting: animations become crossfades/snaps (kits). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** The app's Ambient glow setting (Profile / Settings). */
val LocalAmbientGlowEnabled = staticCompositionLocalOf { true }

/**
 * OS-level reduce motion (Android animator scale 0, iOS Reduce Motion, `prefers-reduced-motion`), observed: the
 * state follows the OS setting while the app runs.
 */
@Composable
expect fun rememberSystemReduceMotion(): State<Boolean>

/**
 * The app's appearance settings. Defaults until L1's settings store lands; P8/P9 pass the persisted values.
 */
@Immutable
data class AppearanceSettings(
    val reduceMotion: Boolean = false,
    val ambientGlow: Boolean = true,
    val haptics: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.Medium,
)

private val CineVerseColors = darkColorScheme(
    primary = Tokens.Colors.Accent,
    onPrimary = Tokens.Colors.OnAccent,
    secondary = Tokens.Colors.Secondary,
    onSecondary = Tokens.Colors.TextPrimary,
    tertiary = Tokens.Colors.Gold,
    onTertiary = Tokens.Colors.OnGold,
    background = Tokens.Colors.Background,
    onBackground = Tokens.Colors.TextPrimary,
    surface = Tokens.Colors.Surface,
    onSurface = Tokens.Colors.TextPrimary,
    surfaceVariant = Tokens.Colors.Tile,
    onSurfaceVariant = Tokens.Colors.TextSecondary,
    surfaceContainer = Tokens.Colors.SurfaceRaised,
    surfaceContainerLow = Tokens.Colors.SurfaceRaised,
    surfaceContainerHigh = Tokens.Colors.SurfaceRaised,
    outline = Tokens.Colors.Stroke,
    outlineVariant = Tokens.Colors.Stroke,
    error = Tokens.Colors.Danger,
    scrim = Tokens.Colors.Background,
)

private val CineVerseShapes = Shapes(
    small = RoundedCornerShape(Tokens.Radius.Tile),
    medium = RoundedCornerShape(Tokens.Radius.Card),
    large = RoundedCornerShape(Tokens.Radius.CardLarge),
    extraLarge = RoundedCornerShape(Tokens.Radius.Dialog),
)

/** Display text (Bricolage Grotesque), tight tracking per the kits (-0.02 to -0.04 em). */
@Composable
@ReadOnlyComposable
fun displayStyle(size: TextUnit, weight: FontWeight = FontWeight.ExtraBold, trackingEm: Float = -0.02f) =
    TextStyle(fontFamily = LocalCvFonts.current.display, fontWeight = weight, fontSize = size, letterSpacing = trackingEm.em)

/** Body text (Inter). */
@Composable
@ReadOnlyComposable
fun bodyStyle(size: TextUnit, weight: FontWeight = FontWeight.Normal, trackingEm: Float = 0f) =
    TextStyle(fontFamily = LocalCvFonts.current.body, fontWeight = weight, fontSize = size, letterSpacing = trackingEm.em)

/** M3 roles mapped onto the kit scale the shell uses (tokens.json typography.scaleMobile / scaleWeb). */
private fun cineVerseTypography(shell: ShellStyle, fonts: CvFonts): Typography {
    fun display(size: TextUnit, tracking: Float = -0.03f, weight: FontWeight = FontWeight.ExtraBold) =
        TextStyle(fontFamily = fonts.display, fontWeight = weight, fontSize = size, letterSpacing = tracking.em)
    fun body(size: TextUnit, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = fonts.body, fontWeight = weight, fontSize = size)
    return when (shell) {
        ShellStyle.Mobile -> with(Tokens.Typography.ScaleMobile) {
            Typography(
                displayLarge = display(LargeTitle), displayMedium = display(HeroTitle), displaySmall = display(DetailTitle, -0.02f),
                headlineLarge = display(LargeTitle), headlineMedium = display(DetailTitle, -0.02f), headlineSmall = display(22.sp, -0.02f),
                titleLarge = display(RowTitle, -0.01f, FontWeight.Bold), titleMedium = body(Body, FontWeight.SemiBold), titleSmall = body(Label, FontWeight.SemiBold),
                bodyLarge = body(Body), bodyMedium = body(14.sp), bodySmall = body(Caption),
                labelLarge = body(Body, FontWeight.SemiBold), labelMedium = body(Label, FontWeight.SemiBold), labelSmall = body(Caption, FontWeight.SemiBold),
            )
        }
        ShellStyle.Web -> with(Tokens.Typography.ScaleWeb) {
            Typography(
                displayLarge = display(Hero, -0.035f), displayMedium = display(DetailTitle), displaySmall = display(PageTitle),
                headlineLarge = display(PageTitle), headlineMedium = display(34.sp, -0.02f), headlineSmall = display(SectionTitle, -0.02f),
                titleLarge = body(20.sp, FontWeight.Bold), titleMedium = body(Card, FontWeight.Bold), titleSmall = body(14.sp, FontWeight.SemiBold),
                bodyLarge = body(Body), bodyMedium = body(15.sp), bodySmall = body(13.sp),
                labelLarge = body(15.sp, FontWeight.SemiBold), labelMedium = body(14.sp, FontWeight.SemiBold), labelSmall = body(12.sp, FontWeight.Bold),
            )
        }
    }
}

/** Dark-only Material 3 theme from the kits' tokens; provides the shell style, fonts, motion, glow and haptics locals. */
@Composable
fun CineVerseTheme(shell: ShellStyle, settings: AppearanceSettings = AppearanceSettings(), content: @Composable () -> Unit) {
    val fonts = CvFonts(
        display = FontFamily(
            Font(Res.font.bricolage_grotesque_medium, FontWeight.Medium),
            Font(Res.font.bricolage_grotesque_bold, FontWeight.Bold),
            Font(Res.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold),
        ),
        body = FontFamily(
            Font(Res.font.inter_regular, FontWeight.Normal),
            Font(Res.font.inter_medium, FontWeight.Medium),
            Font(Res.font.inter_semibold, FontWeight.SemiBold),
            Font(Res.font.inter_bold, FontWeight.Bold),
        ),
    )
    val typography = cineVerseTypography(shell, fonts)
    val systemReduceMotion = rememberSystemReduceMotion()
    MaterialTheme(colorScheme = CineVerseColors, typography = typography, shapes = CineVerseShapes) {
        CompositionLocalProvider(
            LocalShellStyle provides shell,
            LocalCvFonts provides fonts,
            // Computed at each reader, so an OS change recomposes only the primitives that read it.
            LocalReduceMotion providesComputed { systemReduceMotion.value || settings.reduceMotion },
            LocalAmbientGlowEnabled provides settings.ambientGlow,
            LocalHaptics provides rememberPlatformHaptics(settings.haptics, settings.hapticStrength),
            LocalContentColor provides Tokens.Colors.TextPrimary,
            LocalTextStyle provides typography.bodyLarge,
            content = content,
        )
    }
}
