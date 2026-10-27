package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Person-only synthetic catalog and cold preferences; no network or account mutations. */
internal class MobilePersonComponentFixture(val scope: CoroutineScope) {
    val store = MobileSettingsStore()
    val settings = SettingsRepository(store)
    val api = MobilePersonComponentApi()
    val generation = GenerationCounter()
    val catalog = CatalogRepository(api, generation, settings::current)
    val auth = MobileAuth()
    val library = LibraryRepository(auth.session, GuestLibrarySource(MobileGuestStore()), object : AccountLibrarySource {
        override suspend fun page(session: com.techgv.cineverse.data.auth.AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage = error("No account reads")
        override fun invalidateLists() = Unit
        override suspend fun states(session: com.techgv.cineverse.data.auth.AuthSession, ref: MediaRef): LibraryState = error("No account reads")
        override suspend fun write(session: com.techgv.cineverse.data.auth.AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState): Unit = error("No account writes")
    })
    val graph = MobileApplication(catalog, generation, settings, auth, library, RecentQueryStore(store), scope)
    val raw = MutableStateFlow<Result<Settings>>(Result.success(Settings()))
    var subscriptions = 0
    var readers = 0
    val rawSettings: Flow<Settings> = flow {
        subscriptions++
        readers++
        try { raw.collect { emit(it.getOrThrow()) } } finally { readers-- }
    }
    fun model(id: Int = 42, saved: SavedStateHandle = SavedStateHandle()) =
        MobilePersonViewModel(graph, id, saved, rawSettings, scope)
    fun failRaw() { raw.value = Result.failure(StorageException("synthetic Person preferences")) }
}

internal class MobilePersonComponentApi : MobileUnusedTmdbApi() {
    val calls = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var stubborn = false
    var failure = false
    var wrongId = false
    var englishWrongId = false
    var blankLocalized = false
    var empty = false
    override suspend fun configuration() = ConfigurationDto()
    override suspend fun person(id: Int, language: String, append: String): PersonDetailDto {
        calls += "$id/$language"
        val wait = gate
        val wrong = wrongId || language == "en-US" && englishWrongId
        if (stubborn) withContext(NonCancellable) { wait?.await() } else wait?.await()
        if (failure) throw TmdbException(TmdbError.Network)
        return PersonDetailDto(if (wrong) id + 1 else id, name = "Person $language",
            biography = if (blankLocalized && language != "en-US") "" else "Actual biography. ".repeat(80),
            birthday = "1970-01-02", deathday = "2020-03-04", placeOfBirth = "Actual birthplace",
            knownForDepartment = "Acting", combinedCredits = CombinedCreditsDto(cast = if (empty) emptyList() else (1..30).map {
                PersonCreditDto(id = 700 + it, mediaType = if (it == 1) "tv" else "movie", title = "Actual credit $it",
                    releaseDate = "2025-01-02", character = "Actual role $it", voteCount = 100 - it)
            }))
    }
}
