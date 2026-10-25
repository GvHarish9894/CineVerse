package com.techgv.cineverse.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.WindowClass
import com.techgv.cineverse.ui.components.initialsOf
import com.techgv.cineverse.ui.components.oneDecimal
import com.techgv.cineverse.ui.components.scoreColor
import com.techgv.cineverse.ui.components.scorePercent
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.gallery.hexColor
import com.techgv.cineverse.ui.gallery.parseGallerySamples
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.haptics.HapticStrength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScoreTest {
    @Test
    fun thresholdsMatchTv() {
        assertEquals(Tokens.Colors.ScoreHigh, scoreColor(70))
        assertEquals(Tokens.Colors.ScoreHigh, scoreColor(100))
        assertEquals(Tokens.Colors.ScoreMid, scoreColor(69))
        assertEquals(Tokens.Colors.ScoreMid, scoreColor(40))
        assertEquals(Tokens.Colors.ScoreLow, scoreColor(39))
        assertEquals(Tokens.Colors.ScoreLow, scoreColor(0))
        assertEquals(Color(0xFF21D07A), Tokens.Colors.ScoreHigh)
        assertEquals(Color(0xFFD2D531), Tokens.Colors.ScoreMid)
        assertEquals(Color(0xFFDB2360), Tokens.Colors.ScoreLow)
    }

    @Test
    fun percentRoundsAndClamps() {
        assertEquals(81, scorePercent(8.1))
        assertEquals(70, scorePercent(6.95))
        assertEquals(0, scorePercent(-1.0))
        assertEquals(100, scorePercent(10.4))
    }

    @Test
    fun formatting() {
        assertEquals("8.0", oneDecimal(8.0))
        assertEquals("7.5", oneDecimal(7.46))
        assertEquals("AK", initialsOf("Aria  Kapoor Rao"))
    }
}

class WindowClassTest {
    @Test
    fun breakpoints() {
        assertEquals(WindowClass.Phone, WindowClass.fromWidth(320.dp))
        assertEquals(WindowClass.Phone, WindowClass.fromWidth(640.dp))
        assertEquals(WindowClass.Tablet, WindowClass.fromWidth(640.5.dp))
        assertEquals(WindowClass.Tablet, WindowClass.fromWidth(641.dp))
        assertEquals(WindowClass.Tablet, WindowClass.fromWidth(999.dp))
        assertEquals(WindowClass.Tablet, WindowClass.fromWidth(999.9.dp))
        assertEquals(WindowClass.Desktop, WindowClass.fromWidth(1000.dp))
        assertEquals(WindowClass.Desktop, WindowClass.fromWidth(1440.dp))
    }
}

class TokensTest {
    @Test
    fun generatedValues() {
        assertEquals(Color(0xFF07060F), Tokens.Colors.Background)
        // rgba(255,255,255,0.06) → alpha round(0.06 × 255) = 15
        assertEquals(Color(0x0FFFFFFF), Tokens.Colors.Tile)
        assertEquals(Color(0xD1141226), Tokens.Colors.SurfaceGlass)
        assertEquals(15.sp, Tokens.Typography.ScaleMobile.Body)
        assertEquals(16.5.sp, Tokens.Typography.ScaleWeb.Body)
        assertEquals(118.dp, Tokens.Size.MobilePoster.width)
        assertEquals(177.dp, Tokens.Size.MobilePoster.height)
        assertEquals(400, Tokens.Motion.Sheet.durationMillis)
        assertEquals(0.60f, Tokens.Glow.AmbientOpacityMobile)
        assertEquals(0.45f, Tokens.Glow.AmbientOpacityWeb)
    }

    @Test
    fun tokenHapticPatternsExpandWithBase() {
        assertEquals(listOf(20, 45, 20), Tokens.Haptics.Edge.timingsMs(Tokens.Haptics.StrengthMs.Medium))
        assertEquals(listOf(16, 50, 24), Tokens.Haptics.Success.timingsMs(Tokens.Haptics.StrengthMs.Medium))
    }

    @Test
    fun mobileKitHapticsUse6_10_16() {
        assertEquals(listOf(6, 10, 16), HapticStrength.entries.map { it.baseMs })
        assertEquals(listOf(10), HapticKind.Tap.pattern.timingsMs(HapticStrength.Medium.baseMs))
        assertEquals(listOf(20), HapticKind.Select.pattern.timingsMs(HapticStrength.Strong.baseMs))
        assertEquals(listOf(10, 30, 10), HapticKind.ToggleOn.pattern.timingsMs(10))
        assertEquals(listOf(14, 50, 20), HapticKind.Success.pattern.timingsMs(10))
        assertEquals(listOf(12, 60, 12), HapticKind.Warn.pattern.timingsMs(HapticStrength.Light.baseMs))
    }
}

class GallerySamplesTest {
    @Test
    fun parsesPreviewModels() {
        val json = """
            {"movies":[{"id":1,"title":"Neon Tide","release_date":"2026-03-13","vote_average":8.1,"genres":[{"id":1,"name":"Thriller"}],"_ui":{"c1":"#FF3D7F","c2":"#6B21FF"}}],
             "tv":[{"id":2,"name":"Signal","first_air_date":"2024-04-12","_ui":{"c1":"#38BDF8","c2":"#7C3AED"},"extra":1}],
             "people":[{"id":3,"name":"Aria Kapoor","known_for_department":"Acting","known_for":[{"title":"Neon Tide"}],"_ui":{"c1":"#FF3D7F","c2":"#7C3AED"}}],
             "lists":{}}
        """.trimIndent()
        val samples = parseGallerySamples(json)
        assertEquals("Neon Tide", samples.movies.single().name)
        assertEquals(1, samples.movies.single().voteCount)
        assertEquals("2024", samples.tv.single().year)
        assertEquals(0, samples.tv.single().voteCount)
        assertEquals(listOf("Neon Tide"), samples.people.single().knownFor)
        assertEquals(Color(0xFFFF3D7F), samples.movies.single().palette.c1)
    }

    @Test
    fun rejectsMalformedColours() {
        assertFailsWith<IllegalArgumentException> { hexColor("FF3D7F") }
        assertFailsWith<IllegalArgumentException> { hexColor("#FFF") }
    }
}
