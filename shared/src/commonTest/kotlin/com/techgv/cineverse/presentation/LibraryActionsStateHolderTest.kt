package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.LibraryWriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Identity capture at click time and dialog deferral (rules of tvApp LibraryActionsControllerTest, without toasts). */
class LibraryActionsStateHolderTest {
    private val account = FakeAccountLibrary()
    private val a = session("a", generation = 1)
    private val b = session("b", generation = 2)
    private val identity = MutableStateFlow<AuthSession?>(a)
    private val repository = LibraryRepository(identity, GuestLibrarySource(InMemoryGuestLibraryStore()), account)
    private val outcomes = mutableListOf<LibraryWriteOutcome>()
    private val movie = title(1)

    /** Actions dispatch on the test scheduler: launched on click, run at the next runCurrent. */
    private fun TestScope.holder() = LibraryActionsStateHolder(repository, backgroundScope, backgroundScope) { outcomes += it }.also { it.onIdentity(a) }

    private fun LibraryActionsStateHolder.switchTo(c: AuthSession?) {
        identity.value = c
        onIdentity(c)
    }

    @Test fun aToggleClickedUnderAAndDispatchedAfterASwitchToBIsSupersededAndBIsUntouched() = runTest {
        val holder = holder()
        holder.toggleWatchlist(movie)
        holder.toggleFavourite(movie)
        holder.switchTo(b)
        runCurrent()
        assertTrue(account.writes.isEmpty(), "never retargeted to B")
        assertTrue(outcomes.isEmpty(), "superseded: nothing reported")

        holder.toggleWatchlist(movie) // clicked under B: written to B
        runCurrent()
        assertEquals(listOf("watchlist/movie:1=true"), account.writes)
        val outcome = outcomes.single()
        assertEquals(LibraryWrite.ToggleWatchlist, outcome.write)
        assertEquals(true, (outcome.result as LibraryWriteResult.Done).synced)
    }

    @Test fun aRatePromptRequestedBeforeThePlayerStartedIsNotShownOverIt() = runTest {
        val holder = holder()
        holder.openRate(movie)
        holder.setPlayerActive(true)
        runCurrent()
        assertNull(holder.ratePrompt.first())

        holder.setPlayerActive(false)
        assertEquals(movie, holder.ratePrompt.first()?.title)
    }

    @Test fun aRatePromptWhoseSignInChangedBeforeItOpenedIsNotShown() = runTest {
        val holder = holder()
        holder.openRate(movie)
        holder.switchTo(b)
        runCurrent()
        assertNull(holder.ratePrompt.first())
    }

    @Test fun submittingARatingWritesForTheIdentityThePromptWasOpenedUnder() = runTest {
        val holder = holder()
        holder.openRate(movie)
        runCurrent()
        assertEquals(a, holder.ratePrompt.first()?.identity)
        holder.submitRating(7.5)
        runCurrent()
        assertNull(holder.ratePrompt.first())
        assertEquals(listOf("rating/movie:1=7.5"), account.writes)
        assertEquals(LibraryWrite.Rate(7.5), outcomes.single().write)
    }
}
