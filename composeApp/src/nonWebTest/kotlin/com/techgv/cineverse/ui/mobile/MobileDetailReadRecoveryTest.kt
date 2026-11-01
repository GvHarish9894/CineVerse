package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.presentation.SectionErrorKind
import com.techgv.cineverse.presentation.SectionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileDetailReadRecoveryTest {
    @Test fun returningWhileReverifyingShowsNoWarning() {
        assertFalse(showsReadRecovery(MobileDetailState(stale = true, section = SectionState.Loading)))
    }

    @Test fun failedReverificationShowsTheWarning() {
        assertTrue(showsReadRecovery(MobileDetailState(stale = true, section = SectionState.Error(SectionErrorKind.Offline))))
    }

    @Test fun failedPreferencesAlwaysShowTheWarning() {
        assertTrue(showsReadRecovery(MobileDetailState(settingsFailed = true, stale = true, section = SectionState.Loading)))
        assertTrue(showsReadRecovery(MobileDetailState(settingsFailed = true)))
    }

    @Test fun verifiedDetailShowsNoWarning() {
        assertFalse(showsReadRecovery(MobileDetailState()))
    }
}
