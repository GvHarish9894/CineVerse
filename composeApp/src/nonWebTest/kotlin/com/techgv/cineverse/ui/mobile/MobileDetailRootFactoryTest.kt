package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileDetailRootFactoryTest {
    @Test fun factoryUsesExactRouteSavedHandleRawSettingsAndActualGuestOrigin() = runTest {
        val f = MobileDetailRootFixture(backgroundScope)
        f.auth.emissions.emit(null)
        runCurrent()
        val saved = SavedStateHandle(mapOf("mobileDetail.segment" to "Cast", "mobileDetail.expanded" to true))
        val model = f.graph.detail("movie", 101, saved, backgroundScope)
        val subscriptions = f.store.subscriptions
        runCurrent()
        assertEquals(subscriptions, f.store.subscriptions)
        assertTrue(f.api.detailCalls.isEmpty())
        model.setActive(true); runCurrent()
        assertEquals(MediaRef(MediaType.Movie, 101), model.ref)
        assertEquals(listOf("movie/101/en-US"), f.api.detailCalls)
        assertTrue(f.store.subscriptions > subscriptions, "Factory must collect the raw store, not graph's retained settings")
        assertEquals(MobileDetailSegment.Cast, model.state.value.segment)
        assertTrue(model.state.value.expanded)
        assertTrue(model.state.value.account.known, "Guest source identity must be from stateWithOrigin")
        model.setExpanded(false)
        assertEquals(false, saved.get<Boolean>("mobileDetail.expanded"))
        model.act(LibraryKind.FAVOURITE, model.state.value.authority); runCurrent()
        assertTrue(model.state.value.account.favourite)
        assertEquals(listOf(LibraryKind.FAVOURITE), f.guest.rows.value.map { it.kind })
        model.close(); f.graph.close()
    }

    @Test fun factoryForwardsFullAccountOriginAndNeverRetargetsOldAuthority() = runTest {
        val f = MobileDetailRootFixture(backgroundScope)
        val first = AuthSession("root-account", "Same label", 9_007_199_254_740_993L)
        f.auth.emissions.emit(first); runCurrent()
        val model = f.graph.detail("movie", 765, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        assertTrue(model.state.value.account.known)
        assertEquals(first, model.state.value.authority?.identity?.session)
        assertEquals(listOf(first to MediaRef(MediaType.Movie, 765)), f.accountReads)
        val rendered = model.state.value.authority
        f.auth.emissions.emit(first.copy(sessionGeneration = first.sessionGeneration + 1)); runCurrent()
        model.act(LibraryKind.WATCHLIST, rendered); runCurrent()
        assertTrue(f.accountWrites.isEmpty())
        model.close(); f.graph.close()
    }

    @Test fun invalidRoutesAndMalformedSavedStateMakeNoRawOrLibraryOrCatalogReads() = runTest {
        val f = MobileDetailRootFixture(backgroundScope)
        f.auth.emissions.emit(null); runCurrent()
        val subscriptions = f.store.subscriptions
        for ((media, id) in listOf("Movie" to 101, "person" to 101, "tv" to 0, "movie" to -1)) {
            val saved = SavedStateHandle(mapOf("sentinel" to "unchanged"))
            val model = f.graph.detail(media, id, saved, backgroundScope)
            model.setActive(true); model.contextChanged(); model.retryReads(); runCurrent()
            assertTrue(model.state.value.invalid)
            assertNull(model.ref)
            assertEquals("unchanged", saved.get<String>("sentinel"))
            model.close()
        }
        val malformed = SavedStateHandle(mapOf("mobileDetail.Cast.index" to "wrong type"))
        val model = f.graph.detail("movie", 101, malformed, backgroundScope)
        model.setActive(true); runCurrent()
        assertTrue(model.state.value.invalid)
        assertEquals("wrong type", malformed.get<String>("mobileDetail.Cast.index"))
        assertEquals(subscriptions, f.store.subscriptions)
        assertEquals(0, f.guest.reads)
        assertTrue(f.accountReads.isEmpty())
        assertTrue(f.api.detailCalls.isEmpty())
        model.close(); f.graph.close()
    }

    @Test fun factoryRawFailureDoesNotBecomeRetainedSettingsSuccessAndExplicitRecoveryDoesNotReplay() = runTest {
        val f = MobileDetailRootFixture(backgroundScope)
        f.auth.emissions.emit(null); runCurrent()
        val model = f.graph.detail("movie", 101, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        val settings = f.graph.settings.value
        f.store.fail = true
        f.store.values.value = mapOf("language" to "hi-IN")
        runCurrent()
        assertEquals(settings, f.graph.settings.value)
        assertTrue(model.state.value.settingsFailed)
        assertNull(model.state.value.authority)
        f.store.fail = false
        model.retryReads(); runCurrent()
        assertFalse(model.state.value.settingsFailed)
        assertEquals("hi-IN", model.state.value.authority?.context?.language)
        assertTrue(f.guest.rows.value.isEmpty())
        model.close(); f.graph.close()
    }

    @Test fun identityReadFailureClosesQueuedRateWithoutGuestEmissionOrReplay() = runTest {
        val f = MobileDetailRootFixture(backgroundScope)
        f.auth.emissions.emit(null); runCurrent()
        val prompts = mutableListOf<RatePrompt?>()
        backgroundScope.launch { f.graph.actions.ratePrompt.collect { prompts += it } }
        val title = MovieDetailDto(101, "Queued root title").toDetail("US").toTitle()
        f.guest.gate = CompletableDeferred()
        f.graph.actions.openRate(title, null); runCurrent()
        f.auth.fail = true
        f.auth.emissions.emit(null); runCurrent()
        assertTrue(f.graph.identityFailed.value)
        assertNull(f.graph.identity.value)
        f.auth.fail = false
        f.guest.gate!!.complete(Unit); runCurrent()
        assertTrue(prompts.all { it == null })
        f.graph.retryIdentity(); runCurrent()
        assertEquals(MobileIdentity(null), f.graph.identity.value)
        assertTrue(prompts.all { it == null }, "Explicit read recovery must not replay a queued rate request")
        f.graph.close()
    }
}
