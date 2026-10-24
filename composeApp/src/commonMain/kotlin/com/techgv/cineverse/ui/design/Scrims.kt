package com.techgv.cineverse.ui.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Scrims drawn over content, with the exact stops from the prototypes' CSS (tokens.json `scrim.*` only describes
 * them in prose). `linear-gradient(0deg, A 0%, B 30%…)` runs bottom→top, so stops are mirrored onto y.
 */
object Scrims {
    private val Bg = Tokens.Colors.Background
    private fun bg(alpha: Float) = Bg.copy(alpha = alpha)

    /** Overlay alpha behind sheets (mobile .62 + 6 blur) and dialogs (web .72 + 12 blur); colour rgba(5,4,12). */
    val SheetScrim = Color(5, 4, 12).copy(alpha = 0.62f)
    val DialogScrim = Color(5, 4, 12).copy(alpha = 0.72f)

    /** Mobile hero slide: bottom bg → .86 @30% → 0 @60%, plus top .5 → 0 @22%. */
    fun Modifier.mobileHeroScrim(): Modifier = overlay {
        fromBottom(0f to Bg, 0.30f to bg(0.86f), 0.60f to bg(0f))
        fromTop(0f to bg(0.5f), 0.22f to bg(0f))
    }

    /** Mobile detail header: bottom bg → .75 @28% → 0 @62%, plus top .55 → 0 @30%. */
    fun Modifier.mobileDetailHeaderScrim(): Modifier = overlay {
        fromBottom(0f to Bg, 0.28f to bg(0.75f), 0.62f to bg(0f))
        fromTop(0f to bg(0.55f), 0.30f to bg(0f))
    }

    /** Web home hero: left bg → .9 @28% → .45 @58% → 0 @82%, plus bottom 55% height bg → .7 @40% → 0. */
    fun Modifier.webHeroScrim(): Modifier = overlay {
        fromLeft(0f to Bg, 0.28f to bg(0.9f), 0.58f to bg(0.45f), 0.82f to bg(0f))
        fromBottom(0f to Bg, 0.40f to bg(0.7f), 1f to bg(0f), heightFraction = 0.55f)
    }

    /** Web detail hero band: horizontal .96 → .86 @38% → .55, plus bottom fade bg → 0 @30%. */
    fun Modifier.webDetailHeroScrim(): Modifier = overlay {
        fromLeft(0f to bg(0.96f), 0.38f to bg(0.86f), 1f to bg(0.55f))
        fromBottom(0f to Bg, 0.30f to bg(0f))
    }

    /** Poster/landscape art: rgba(0,0,0,.62) at the bottom → 0 @58% (keeps titles legible). */
    fun Modifier.posterBottomScrim(): Modifier = overlay {
        fromBottom(0f to Color.Black.copy(alpha = 0.62f), 0.58f to Color.Black.copy(alpha = 0f))
    }

    private fun Modifier.overlay(draw: DrawScope.() -> Unit) = drawWithContent {
        drawContent()
        draw()
    }

    private fun DrawScope.fromBottom(vararg stops: Pair<Float, Color>, heightFraction: Float = 1f) {
        val h = size.height * heightFraction
        val top = size.height - h
        val mirrored = stops.map { (p, c) -> (1f - p) to c }.sortedBy { it.first }.toTypedArray()
        drawRect(Brush.verticalGradient(*mirrored, startY = top, endY = size.height), topLeft = Offset(0f, top), size = Size(size.width, h))
    }

    private fun DrawScope.fromTop(vararg stops: Pair<Float, Color>) =
        drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = size.height))

    private fun DrawScope.fromLeft(vararg stops: Pair<Float, Color>) =
        drawRect(Brush.horizontalGradient(*stops, startX = 0f, endX = size.width))
}
