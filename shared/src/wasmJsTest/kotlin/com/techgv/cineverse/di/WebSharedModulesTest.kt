package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.WebAuthRepositoryStub
import com.techgv.cineverse.data.settings.BrowserSettingsStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.settings.SettingsStore
import com.techgv.cineverse.data.tmdb.Transport
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSharedModulesTest {
    @Test
    fun everyDefinitionResolvesWithTheWebPlatformModule() {
        val app = koinApplication { modules(sharedModules(WebConfig(proxyBaseUrl = "/api"))) }
        try {
            val resolved = app.koin.resolveEveryDefinition()
            val expected = listOf(
                GenerationCounter::class, CoroutineScope::class, SettingsRepository::class, SettingsStore::class,
                Transport.Proxy::class, AuthRepository::class,
            )
            assertTrue(resolved.containsAll(expected), "missing ${expected - resolved.toSet()}")
            assertIs<BrowserSettingsStore>(app.koin.get<SettingsStore>())
            assertIs<WebAuthRepositoryStub>(app.koin.get<AuthRepository>())
            assertEquals("/api", app.koin.get<Transport.Proxy>().baseUrl)
        } finally {
            app.close()
        }
    }

    @Test
    fun theAuthStubIsAlwaysAGuestAndSignOutChangesNothing() = runTest {
        val auth = WebAuthRepositoryStub()
        assertNull(auth.session.first())
        var signedOutLocally = false
        auth.signOut { signedOutLocally = true }
        assertTrue(signedOutLocally)
        assertNull(auth.session.first())
        assertEquals(emptyList(), auth.sessionExpired.toList())
        val error = assertFailsWith<UnsupportedOperationException> { auth.createRequestCode() }
        assertTrue("P9 via /api/auth" in error.message.orEmpty())
    }
}
