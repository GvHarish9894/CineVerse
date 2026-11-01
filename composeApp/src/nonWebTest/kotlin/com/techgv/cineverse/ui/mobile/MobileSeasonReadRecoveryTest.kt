package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.presentation.SectionErrorKind
import com.techgv.cineverse.presentation.SeasonScreenState
import com.techgv.cineverse.presentation.SectionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileSeasonReadRecoveryTest {
    @Test fun waitingForPreferencesShowsNoWarning() {
        assertFalse(showsSeasonReadRecovery(MobileSeasonState()))
    }

    @Test fun returningWhileReverifyingShowsNoWarning() {
        assertFalse(showsSeasonReadRecovery(MobileSeasonState(stale = true)))
    }

    @Test fun failedReverificationShowsTheWarning() {
        val current = SeasonScreenState(show = SectionState.Error(SectionErrorKind.Offline))
        assertTrue(showsSeasonReadRecovery(MobileSeasonState(stale = true, current = current)))
    }

    @Test fun failedPreferencesAlwaysShowTheWarning() {
        assertTrue(showsSeasonReadRecovery(MobileSeasonState(settingsFailed = true)))
    }
}
