package com.techgv.cineverse.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class BackPolicyTest {
    private val home = BackContext(onLogin = false, hasPushedRoute = false, focusInRail = false, onHome = true, homeAtOrigin = true)

    @Test
    fun `precedence follows spec 1_2`() {
        assertEquals(BackAction.ShowExitDialog, backAction(home.copy(onLogin = true, onHome = false)))
        assertEquals(BackAction.Pop, backAction(home.copy(onLogin = true, onHome = false, hasPushedRoute = true)))
        assertEquals(BackAction.FocusContent, backAction(home.copy(focusInRail = true, homeAtOrigin = false)))
        assertEquals(BackAction.Pop, backAction(home.copy(onHome = false, hasPushedRoute = true)))
        assertEquals(BackAction.ResetToHome, backAction(home.copy(onHome = false)))
        assertEquals(BackAction.FocusHomeOrigin, backAction(home.copy(homeAtOrigin = false)))
        assertEquals(BackAction.ShowExitDialog, backAction(home))
    }
}
