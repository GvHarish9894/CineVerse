package com.techgv.cineverse.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Card → hero with the same key: a shared-bounds transition runs, unless Reduce motion makes it a plain cut. */
@RunWith(AndroidJUnit4::class)
class SharedMediaTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sameKey_runsSharedTransition() = assertTransitionActive(reduceMotion = false, expected = true)

    @Test
    fun reduceMotion_isPlainCut() = assertTransitionActive(reduceMotion = true, expected = false)

    private fun assertTransitionActive(reduceMotion: Boolean, expected: Boolean) {
        var detail by mutableStateOf(false)
        lateinit var scope: SharedTransitionScope
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings(motion = reduceMotion)) {
                SharedTransitionLayout {
                    scope = this
                    ProvideSharedTransition(this) {
                        AnimatedContent(detail, label = "screen") { showDetail ->
                            ProvideSharedMediaVisibility(this) {
                                Box(rememberSharedMedia("poster:movie:42").size(if (showDetail) 240.dp else 140.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        detail = true
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(expected, scope.isTransitionActive)
    }
}
