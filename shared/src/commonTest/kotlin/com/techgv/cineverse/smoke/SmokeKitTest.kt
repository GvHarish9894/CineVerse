package com.techgv.cineverse.smoke

import androidx.lifecycle.SavedStateHandle
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeKitTest {
    @Test
    fun visitsAreKeptInSavedStateHandle() {
        val handle = SavedStateHandle()
        SmokeViewModel(handle).apply {
            recordVisit()
            recordVisit()
        }
        // A recreated ViewModel over the same handle (process-death restore) sees the saved value.
        assertEquals(2, SmokeViewModel(handle).visits.value)
    }

    @Test
    fun fetchImageBaseUrlSendsBearerTokenAndParsesConfiguration() = runTest {
        val engine = MockEngine { request ->
            assertEquals(TMDB_CONFIGURATION_URL, request.url.toString())
            assertEquals("Bearer test-read-token", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"images":{"base_url":"http://image.tmdb.org/t/p/","secure_base_url":"https://image.tmdb.org/t/p/","poster_sizes":["w92"]},"change_keys":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val baseUrl = fetchImageBaseUrl(tmdbHttpClient(engine), readToken = "test-read-token")

        assertEquals("https://image.tmdb.org/t/p/", baseUrl)
        assertEquals(1, engine.requestHistory.size)
    }
}
