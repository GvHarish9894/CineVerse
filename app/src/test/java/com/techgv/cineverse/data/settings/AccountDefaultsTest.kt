package com.techgv.cineverse.data.settings

import com.techgv.cineverse.data.auth.InMemoryPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountDefaultsTest {
    private val repo = SettingsRepository(InMemoryPreferencesStore())
    private val defaults = AccountDefaults("aoid", "hi-IN", "US", includeAdult = true)

    @Test
    fun `apply writes language, region and include_adult and ends the offer`() = runTest {
        repo.offerAccountDefaults(defaults)
        assertEquals(defaults, repo.accountDefaults.first())
        repo.resolveAccountDefaults(apply = true)
        assertNull(repo.accountDefaults.first())
        assertEquals(Settings(language = "hi-IN", region = "US", includeAdult = true), repo.current())
    }

    @Test
    fun `dismiss keeps the settings`() = runTest {
        repo.offerAccountDefaults(defaults)
        repo.resolveAccountDefaults(apply = false)
        assertNull(repo.accountDefaults.first())
        assertEquals(Settings(), repo.current())
    }

    @Test
    fun `an account is offered only on its first sign-in`() = runTest {
        repo.offerAccountDefaults(defaults)
        repo.resolveAccountDefaults(apply = false)
        repo.offerAccountDefaults(defaults)
        assertNull(repo.accountDefaults.first())
        repo.offerAccountDefaults(defaults.copy(accountObjectId = "other"))
        assertEquals("other", repo.accountDefaults.first()?.accountObjectId)
    }
}
