package com.techgv.cineverse.data.settings

import com.techgv.cineverse.data.auth.InMemoryPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountDefaultsTest {
    private val repo = SettingsRepository(InMemoryPreferencesStore())
    private val a = AccountDefaults("a", "hi-IN", "US", includeAdult = true)
    private val b = AccountDefaults("b", "es-ES", "GB", includeAdult = false)

    @Test
    fun `apply writes language, region and include_adult and ends the offer`() = runTest {
        repo.offerAccountDefaults(a)
        assertEquals(mapOf("a" to a), repo.pendingAccountDefaults.first())
        repo.resolveAccountDefaults("a", apply = true)
        assertEquals(emptyMap<String, AccountDefaults>(), repo.pendingAccountDefaults.first())
        assertEquals(Settings(language = "hi-IN", region = "US", includeAdult = true), repo.current())
    }

    @Test
    fun `dismiss keeps the settings and a resolved account is never offered again`() = runTest {
        repo.offerAccountDefaults(a)
        repo.resolveAccountDefaults("a", apply = false)
        assertEquals(Settings(), repo.current())
        repo.offerAccountDefaults(a)
        assertEquals(emptyMap<String, AccountDefaults>(), repo.pendingAccountDefaults.first())
    }

    @Test
    fun `offers are kept per account until resolved, and resolving one never touches another`() = runTest {
        repo.offerAccountDefaults(a)
        repo.offerAccountDefaults(b) // B signs in while A's offer is unresolved
        repo.offerAccountDefaults(a) // A returns: shown before, still unresolved, still offered
        assertEquals(mapOf("a" to a, "b" to b), repo.pendingAccountDefaults.first())

        repo.resolveAccountDefaults("b", apply = true)
        assertEquals(mapOf("a" to a), repo.pendingAccountDefaults.first())
        assertEquals(Settings(language = "es-ES", region = "GB"), repo.current())

        repo.resolveAccountDefaults("b", apply = false) // a repeat for B is a no-op
        assertEquals(mapOf("a" to a), repo.pendingAccountDefaults.first())
    }
}
