package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Real shared settings repository, synthetic storage/auth; no network or account writes. */
internal class MobileProfileComponentFixture(val scope: CoroutineScope) {
    val store = MobileSettingsStore()
    val settings = SettingsRepository(store)
    val auth = MobileProfileAuth()
    val generation = GenerationCounter()
    val catalog = CatalogRepository(object : MobileUnusedTmdbApi() {
        override suspend fun configuration() = ConfigurationDto()
    }, generation, settings::current)
    val library = LibraryRepository(auth.session, GuestLibrarySource(MobileGuestStore()), object : AccountLibrarySource {
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage = error("Profile must not fetch account totals")
        override fun invalidateLists() = Unit
        override suspend fun states(session: AuthSession, ref: MediaRef): LibraryState = error("No Profile library reads")
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState): Unit = error("No Profile account writes")
    })
    val graph = MobileApplication(catalog, generation, settings, auth, library, RecentQueryStore(store), scope)
    fun model(saved: SavedStateHandle = SavedStateHandle()) = MobileProfileViewModel(graph, settings, auth, saved, scope)
    fun failReads() {
        store.fail = true
        store.values.value = store.values.value + ("profile-test-read" to "failure")
    }
}

internal class MobileProfileAuth : AuthRepository {
    val emissions = MutableSharedFlow<AuthSession?>(replay = 1)
    var fail = false
    override val session: Flow<AuthSession?> = flow {
        emissions.collect {
            if (fail) throw StorageException("Synthetic Profile identity read")
            emit(it)
        }
    }
    override val sessionExpired = emptyFlow<Unit>()
    override suspend fun createRequestCode(): RequestCode = error("Root owns sign-in")
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome = error("Root owns approval")
    override suspend fun signOut(onSignedOutLocally: () -> Unit): Unit = error("Root owns sign-out confirmation")
    override suspend fun expireSession(failed: AuthSession): Unit = error("No account writes")
}
