package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlin.test.*

/** Prepared cases only; fake adapter, no real external app. */
@OptIn(ExperimentalTestApi::class)
class MobileTrailerScreenTest {
    private val valid = MobileTrailerRoute("aB3_dE5-gH7", "Trailer title", "Official trailer")

    @Test fun validKeyOpensAndSharesExactYouTubeTargets() = runComposeUiTest {
        val external = RecordingMobileExternalActions()
        setContent { TrailerFixture(valid, external) }
        onNodeWithText("Trailer title").assertIsDisplayed()
        onNodeWithText("Official trailer").assertIsDisplayed()
        onNodeWithText("Open in YouTube").performClick()
        onNodeWithText("Share").performClick()
        runOnIdle { assertEquals(listOf("youtube:aB3_dE5-gH7", "share:https://www.youtube.com/watch?v=aB3_dE5-gH7"), external.calls) }
        onNodeWithText("Couldn't open YouTube.").assertDoesNotExist()
        onNodeWithText("Couldn't open sharing.").assertDoesNotExist()
    }

    @Test fun invalidKeyShowsUnavailableAndNeverCallsThePlatform() = runComposeUiTest {
        val external = RecordingMobileExternalActions()
        setContent { TrailerFixture(MobileTrailerRoute("../x?a=1&b2", "Bad title", "Bad kicker"), external) }
        onNodeWithText("Trailer unavailable. This video link is not valid.").assertIsDisplayed()
        onNodeWithText("Open in YouTube").assertDoesNotExist()
        onNodeWithText("Share").assertDoesNotExist()
        runOnIdle { assertTrue(external.calls.isEmpty()) }
    }

    @Test fun backInvokesOnlyTheRouteBack() = runComposeUiTest {
        val external = RecordingMobileExternalActions()
        var backs = 0
        setContent { TrailerFixture(valid, external) { backs++ } }
        onNodeWithText("Back").performClick()
        runOnIdle { assertEquals(1, backs); assertTrue(external.calls.isEmpty()) }
    }

    @Test fun adapterFailureIsReportedWithoutCrashing() = runComposeUiTest {
        val external = RecordingMobileExternalActions(succeeds = false)
        setContent { TrailerFixture(valid, external) }
        onNodeWithText("Open in YouTube").performClick()
        onNodeWithText("Couldn't open YouTube.").assertIsDisplayed()
        onNodeWithText("Share").performClick()
        onNodeWithText("Couldn't open sharing.").assertIsDisplayed()
        onNodeWithText("Open in YouTube").assertIsDisplayed()
        runOnIdle { assertEquals(listOf("youtube:aB3_dE5-gH7", "share:https://www.youtube.com/watch?v=aB3_dE5-gH7"), external.calls) }
    }

    @Composable
    private fun TrailerFixture(route: MobileTrailerRoute, external: MobileExternalActions, onBack: () -> Unit = {}) {
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
            ProvideWindowClass { MobileTrailerScreen(route, external, onBack) }
        }
    }
}
