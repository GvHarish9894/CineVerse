package com.techgv.cineverse.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBackHandleTest {
    private val handle = HomeBackHandle()
    private val home = BackContext(onLogin = false, hasPushedRoute = false, focusInRail = false, onHome = true, homeAtOrigin = false)

    /** Same dispatch as AppNav's BackHandler for the Home steps. */
    private fun pressBack(): BackAction = backAction(home.copy(homeAtOrigin = handle.atOrigin())).also {
        if (it == BackAction.FocusHomeOrigin) handle.focusOrigin()
    }

    @Test
    fun `first BACK focuses the origin, second shows Exit`() {
        var focusedAtOrigin = false
        var focusOriginCalls = 0
        handle.register(atOrigin = { focusedAtOrigin }, focusOrigin = { focusOriginCalls++; focusedAtOrigin = true })
        assertEquals(BackAction.FocusHomeOrigin, pressBack())
        assertEquals(1, focusOriginCalls)
        assertEquals(BackAction.ShowExitDialog, pressBack())
        assertEquals(1, focusOriginCalls)
    }

    @Test
    fun `unregistered Home is not at origin first, then Exit`() {
        assertEquals(BackAction.FocusHomeOrigin, pressBack())
        assertEquals(BackAction.ShowExitDialog, pressBack())
    }

    @Test
    fun `a disposed registration does not clear a newer one`() {
        val unregisterOld = handle.register(atOrigin = { true }, focusOrigin = {})
        var newCalls = 0
        handle.register(atOrigin = { false }, focusOrigin = { newCalls++ })
        unregisterOld()
        assertEquals(BackAction.FocusHomeOrigin, pressBack())
        assertEquals(1, newCalls)
    }
}
