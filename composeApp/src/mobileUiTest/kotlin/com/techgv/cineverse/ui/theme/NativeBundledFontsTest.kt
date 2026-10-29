package com.techgv.cineverse.ui.theme

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import com.techgv.cineverse.ui.adaptive.ShellStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NativeBundledFontsTest {
    @Test fun defaultNullHookSelectsNativeBundledFontWeights() = runComposeUiTest {
        var defaultIsNull = false
        var selected: CvFonts? = null
        setContent {
            val override = LocalCvFontOverride.current
            CineVerseTheme(ShellStyle.Mobile) {
                val fonts = LocalCvFonts.current
                SideEffect {
                    defaultIsNull = override == null
                    selected = fonts
                }
            }
        }
        waitForIdle()
        assertTrue(defaultIsNull)
        val fonts = assertNotNull(selected)
        val display = assertIs<FontListFontFamily>(fonts.display).fonts
        val body = assertIs<FontListFontFamily>(fonts.body).fonts
        assertEquals(3, display.size)
        assertEquals(4, body.size)
        assertEquals(listOf(FontWeight.Medium, FontWeight.Bold, FontWeight.ExtraBold), display.map { it.weight })
        assertEquals(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold), body.map { it.weight })
    }
}
