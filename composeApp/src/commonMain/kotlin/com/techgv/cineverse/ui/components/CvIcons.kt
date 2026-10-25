package com.techgv.cineverse.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The prototypes' 24×24 stroke icons (stroke 2, round caps/joins); [solid] ones are filled too. Tinted by Icon. */
object CvIcons {
    val Search by lazy { icon("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M20 20l-4.2-4.2") }
    val Home by lazy { icon("M3 11l9-7l9 7 M5.5 9.5V20h13V9.5") }
    val Explore by lazy { icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M15.5 8.5l-2 5l-5 2l2-5z") }
    val Library by lazy { icon("M6 3h12v18l-6-4.5L6 21z") }
    val User by lazy { icon("M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8z M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7") }
    val Play by lazy { icon("M8 5.5v13l10.5-6.5z", solid = true) }
    val Pause by lazy { icon("M8 5v14 M16 5v14") }
    val Plus by lazy { icon("M12 5v14 M5 12h14") }
    val Check by lazy { icon("M5 12.5l4.5 4.5L19 7") }
    val Heart by lazy { icon("M12 20.5s-7.5-4.6-9.3-9.4A5 5 0 0 1 12 6.6a5 5 0 0 1 9.3 4.5c-1.8 4.8-9.3 9.4-9.3 9.4z") }
    val Star by lazy { icon("M12 3.2l2.7 5.6l6.1.9l-4.4 4.3l1 6.1L12 17.2l-5.4 2.9l1-6.1l-4.4-4.3l6.1-.9z") }
    val StarFilled by lazy { icon("M12 3.2l2.7 5.6l6.1.9l-4.4 4.3l1 6.1L12 17.2l-5.4 2.9l1-6.1l-4.4-4.3l6.1-.9z", solid = true) }
    val Close by lazy { icon("M6 6l12 12 M18 6L6 18") }
    val Share by lazy { icon("M12 3v12 M7 8l5-5l5 5 M5 13v7h14v-7") }
    val Info by lazy { icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 11v6 M12 7.5v.5") }
    val ChevronLeft by lazy { icon("M15 6l-6 6l6 6") }
    val ChevronRight by lazy { icon("M9 6l6 6l-6 6") }
    val Vibrate by lazy { icon("M8 4h8v16H8z M4.5 8v8 M19.5 8v8 M1.5 10v4 M22.5 10v4") }
    val Glow by lazy { icon("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4") }
    val Motion by lazy { icon("M3 12h4l3-8l4 16l3-8h4") }
    val Globe by lazy { icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M3 12h18 M12 3c3 3.5 3 14.5 0 18 M12 3c-3 3.5-3 14.5 0 18") }
    val Autoplay by lazy { icon("M3 6h18v12H3z M10 9.5v5l4.5-2.5z") }

    private fun icon(pathData: String, solid: Boolean = false): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = if (solid) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
