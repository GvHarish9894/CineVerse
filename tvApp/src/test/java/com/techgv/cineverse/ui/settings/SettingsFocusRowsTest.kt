package com.techgv.cineverse.ui.settings

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.SavedFocus
import org.junit.Assert.assertEquals
import org.junit.Test

/** §1.3 restore targets for Settings: the target's row index must equal its LazyColumn item index. */
class SettingsFocusRowsTest {
    private val memory = FocusMemory(SavedStateHandle())
    private val restorer = FocusRestorer(memory)
    private val rowKeys = listOf("haptics", "strength", "sounds", "glow", "motion", "language", "region", "adult", "account", "about")

    @Test
    fun `with a pending offer, saved Dismiss restores to lazy item 0, button 1`() {
        memory.save(SavedFocus(PANEL, DEFAULTS_ROW, DEFAULTS_DISMISS, 1))
        assertEquals(0 to 1, restorer.restoreTarget(settingsFocusRows(offerShown = true, rowKeys)))
    }

    @Test
    fun `with a pending offer, a saved setting row restores to its own lazy item, not one before it`() {
        memory.save(SavedFocus(PANEL, "about", "about", 0))
        assertEquals(10 to 0, restorer.restoreTarget(settingsFocusRows(offerShown = true, rowKeys)))
        assertEquals(9 to 0, restorer.restoreTarget(settingsFocusRows(offerShown = false, rowKeys)))
    }

    @Test
    fun `a resolved offer that held focus falls back to the first setting`() {
        memory.save(SavedFocus(PANEL, DEFAULTS_ROW, DEFAULTS_DISMISS, 1))
        assertEquals(0 to 0, restorer.restoreTarget(settingsFocusRows(offerShown = false, rowKeys)))
    }
}
