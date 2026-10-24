package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.haptics.Haptics
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MobileHomeHapticsTest {
    @Test fun mountedPagerUsesOnlyCurrentEnabledAndStrengthService() = runComposeUiTest {
        val ticks = mutableListOf<String>()
        fun service(strength: String) = Haptics { kind ->
            if (kind == HapticKind.Tick) ticks += strength
        }
        val current = mutableStateOf(service("Light"))
        setContent { MobileTestRoot(haptics = current.value) }
        onNodeWithContentDescription("Featured title 1 of 5").assertIsDisplayed()
        runOnIdle { assertEquals(emptyList(), ticks) }

        onNodeWithTag("home-hero").performTouchInput { swipeLeft() }
        onNodeWithContentDescription("Featured title 2 of 5").assertIsDisplayed()
        runOnIdle { assertEquals(listOf("Light"), ticks) }

        runOnIdle { current.value = Haptics.None }
        onNodeWithContentDescription("Featured title 2 of 5").assertIsDisplayed()
        onNodeWithTag("home-hero").performTouchInput { swipeLeft() }
        onNodeWithContentDescription("Featured title 3 of 5").assertIsDisplayed()
        runOnIdle { assertEquals(listOf("Light"), ticks, "Disabled feedback must not tick an obsolete service") }

        runOnIdle { current.value = service("Medium") }
        onNodeWithContentDescription("Featured title 3 of 5").assertIsDisplayed()
        onNodeWithTag("home-hero").performTouchInput { swipeLeft() }
        onNodeWithContentDescription("Featured title 4 of 5").assertIsDisplayed()
        runOnIdle { assertEquals(listOf("Light", "Medium"), ticks) }

        runOnIdle { current.value = service("Strong") }
        onNodeWithContentDescription("Featured title 4 of 5").assertIsDisplayed()
        onNodeWithTag("home-hero").performTouchInput { swipeLeft() }
        onNodeWithContentDescription("Featured title 5 of 5").assertIsDisplayed()
        runOnIdle { assertEquals(listOf("Light", "Medium", "Strong"), ticks) }
    }
}
