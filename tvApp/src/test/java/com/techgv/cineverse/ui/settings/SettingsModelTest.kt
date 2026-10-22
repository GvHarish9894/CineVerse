package com.techgv.cineverse.ui.settings

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.UserAccessToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsModelTest {
    private val credentials = Credentials(UserAccessToken("t"), AccountObjectId("aoid"), SessionId("s"), AccountId(1), "harish", "Harish")

    @Test
    fun `cycle wraps both ways`() {
        assertEquals(HapticStrength.Strong, HapticStrength.entries.cycle(HapticStrength.Medium, 1))
        assertEquals(HapticStrength.Light, HapticStrength.entries.cycle(HapticStrength.Strong, 1))
        assertEquals("es-ES", Settings.LANGUAGES.cycle("en-US", -1))
        assertEquals("IN", Settings.REGIONS.cycle("GB", 1))
    }

    @Test
    fun `every supported value has a display name`() {
        assertEquals(Settings.LANGUAGES.toSet(), LANGUAGE_NAMES.keys)
        assertEquals(Settings.REGIONS.toSet(), REGION_NAMES.keys)
    }

    @Test
    fun `account defaults map iso codes onto supported values`() {
        val offer = accountDefaultsOffer(credentials, AccountDto(1, language = "hi", region = "US", includeAdult = true), Settings())
        assertEquals(AccountDefaults("aoid", "hi-IN", "US", true), offer)
    }

    @Test
    fun `unsupported language or region keeps the current value`() {
        val offer = accountDefaultsOffer(credentials, AccountDto(1, language = "fr", region = "FR"), Settings(includeAdult = true))
        assertEquals(AccountDefaults("aoid", "en-US", "IN", false), offer)
    }

    @Test
    fun `no offer when the account defaults match the current settings`() {
        assertNull(accountDefaultsOffer(credentials, AccountDto(1, language = "en", region = "IN"), Settings()))
        assertNull(accountDefaultsOffer(credentials, AccountDto(1, language = null, region = null), Settings()))
    }
}
