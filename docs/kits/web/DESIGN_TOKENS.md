# Design tokens

The raw values live in `design/tokens.json`. This page explains them and gives a Compose starting point.

## Colour

| Token | Value | Use |
|---|---|---|
| background | `#07060F` | App background (near-black with a violet tint, never pure black) |
| surface | `#0B0A16` | Inside of score rings, deep panels |
| surfaceRaised | `#16132B` | Sheets, dialogs, menus |
| tile / tileFocused | white 6% / 16% | Fact tiles, review cards, episode rows |
| textPrimary | `#F4F2FF` | Body and titles |
| textSecondary / Tertiary | text 72% / 58% | Meta lines, captions (both pass 4.5:1 on background) |
| accent | `#FF3D7F` | Primary buttons, active tab, selected chips, progress, toggles. Text on accent is `#14030C`, not white (white fails contrast) |
| secondary | `#7C3AED` | Second stop of the logo gradient, ambient fallback |
| focus | `#FFFFFF` | TV focus fill and 3px ring; web keyboard focus outline |
| gold | `#FFC24B` | Ratings, review scores, rated badges |
| score ring | `#21D07A` / `#D2D531` / `#DB2360` / `#6B7280` | ≥70% / ≥40% / below / not rated |

Accent alternates offered in the prototype tweaks: `#22D3EE`, `#A3E635`, `#FFB020`.

Each title has two palette colours (`c1`, `c2`). In the prototype they are hand-picked; in the real app extract them from the poster/backdrop (Android Palette: vibrant + dark-vibrant swatches). They drive:

- the ambient glow behind the screen,
- the focused/hovered card's coloured shadow,
- fallback gradient art when an image is missing.

## Type

- Display: **Bricolage Grotesque** 700/800, tight tracking (-0.02 to -0.04em). Titles, row headers, big numbers.
- Body: **Inter** 400–700.
- Both are Google Fonts (OFL). Bundle them as font resources; don't load at runtime on TV.

Scales per platform are in `tokens.json → typography`. On TV keep body ≥ 16px-equivalent and titles ≥ 20.

## Shape, spacing, size

- Pills (999) for buttons and chips; 14 for cards; 20–30 for sheets/dialogs.
- TV overscan safe area 48 × 27 px at 1280×720 (5%). Content starts at x = 120 when the collapsed rail is shown.
- Mobile gutter 20. Web max content width 1320 with 32 gutters (16 under 640px).

## Compose starting point

```kotlin
object CvColors {
    val Background = Color(0xFF07060F)
    val Surface = Color(0xFF0B0A16)
    val SurfaceRaised = Color(0xFF16132B)
    val TextPrimary = Color(0xFFF4F2FF)
    val TextSecondary = TextPrimary.copy(alpha = 0.72f)
    val TextTertiary = TextPrimary.copy(alpha = 0.58f)
    val Accent = Color(0xFFFF3D7F)
    val OnAccent = Color(0xFF14030C)
    val Secondary = Color(0xFF7C3AED)
    val Gold = Color(0xFFFFC24B)
    val OnGold = Color(0xFF1A1205)
    val ScoreHigh = Color(0xFF21D07A); val ScoreMid = Color(0xFFD2D531); val ScoreLow = Color(0xFFDB2360); val ScoreNone = Color(0xFF6B7280)
    val Tile = Color.White.copy(alpha = 0.06f); val TileFocused = Color.White.copy(alpha = 0.16f)
}

fun scoreColor(pct: Int?) = when {
    pct == null -> CvColors.ScoreNone
    pct >= 70 -> CvColors.ScoreHigh
    pct >= 40 -> CvColors.ScoreMid
    else -> CvColors.ScoreLow
}

/** Ambient glow: draw behind content, blurred. Use Modifier.blur on Android 12+/desktop; on older devices pre-blur a small bitmap. */
@Composable
fun AmbientGlow(c1: Color, c2: Color, strength: Float = 0.7f, modifier: Modifier = Modifier) {
    val a1 by animateColorAsState(c1, tween(700)); val a2 by animateColorAsState(c2, tween(700))
    Box(modifier.fillMaxSize().graphicsLayer { alpha = 0.5f * strength }.drawBehind {
        drawCircle(Brush.radialGradient(listOf(a1, Color.Transparent), center = Offset(size.width * .72f, size.height * .28f), radius = size.minDimension * .6f))
        drawCircle(Brush.radialGradient(listOf(a2, Color.Transparent), center = Offset(size.width * .18f, size.height * .9f), radius = size.minDimension * .55f))
    }.blur(80.dp))
}

/** Left + bottom scrims over a hero backdrop. */
fun Modifier.heroScrims(bg: Color = CvColors.Background) = drawWithContent {
    drawContent()
    drawRect(Brush.horizontalGradient(0f to bg, .27f to bg.copy(.94f), .5f to bg.copy(.62f), .78f to Color.Transparent))
    drawRect(Brush.verticalGradient(.28f to Color.Transparent, .62f to bg.copy(.92f), 1f to bg))
}
```
