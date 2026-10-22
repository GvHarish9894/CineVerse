package com.techgv.cineverse.smoke

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// P0 toolchain smoke only (docs/TOOLCHAIN.md); replaced by the real shared core from P1 on.

/** Proves lifecycle ViewModel + SavedStateHandle on every client target. */
class SmokeViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    val visits: StateFlow<Int> = savedStateHandle.getStateFlow(VISITS_KEY, 0)

    fun recordVisit() {
        savedStateHandle[VISITS_KEY] = visits.value + 1
    }

    companion object {
        const val VISITS_KEY = "visits"
    }
}

/** Key/value settings contract: DataStore on nonWeb, browser storage on wasm. */
interface SettingsStore {
    fun string(key: String): Flow<String?>
    suspend fun setString(key: String, value: String)
    suspend fun remove(key: String)
}

const val TMDB_CONFIGURATION_URL = "https://api.themoviedb.org/3/configuration"

fun tmdbHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = true
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/** GET /3/configuration with the native read token; returns `images.secure_base_url`. */
suspend fun fetchImageBaseUrl(client: HttpClient, readToken: String): String =
    client.get(TMDB_CONFIGURATION_URL) { bearerAuth(readToken) }.body<ConfigurationDto>().images.secureBaseUrl

@Serializable
internal class ConfigurationDto(val images: ImagesDto)

@Serializable
internal class ImagesDto(@SerialName("secure_base_url") val secureBaseUrl: String)
