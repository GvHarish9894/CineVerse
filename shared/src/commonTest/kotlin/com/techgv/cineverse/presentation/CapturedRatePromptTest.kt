package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.AccountLibrarySource
import com.techgv.cineverse.data.library.GuestLibraryRow
import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.GuestLibraryStore
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.LibraryWriteResult
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.Title
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual holder and repository guards, with only account IO and persistence replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturedRatePromptTest {
    private val a = session("account-a", generation = 1)
    private val b = session("account-b", generation = 2)
    private val movie = title(1)
    private val other = title(2)

    @Test fun currentSubmitClosesBeforeDispatchAndWritesOnlyOnce() = runTest {
        val f = Fixture(this, a)
        val expected = f.open(movie)
        f.account.writeGate = CompletableDeferred()
        f.holder.submitRating(8.5, expected)
        assertNull(f.holder.ratePrompt.first())
        f.holder.submitRating(7.5, expected)
        runCurrent()
        assertEquals(listOf("rating/movie:1=8.5"), f.account.writes)
        assertEquals(listOf(a), f.writeSessions)
        assertTrue(f.outcomes.isEmpty())
        f.account.writeGate!!.complete(Unit)
        runCurrent()
        assertEquals(LibraryWrite.Rate(8.5), f.outcomes.single().write)
    }

    @Test fun currentRemoveClosesBeforeDispatchAndWritesOnlyOnce() = runTest {
        val f = Fixture(this, a)
        f.account.ratings[movie.ref] = 6.5
        val expected = f.open(movie)
        f.holder.removeRating(expected)
        assertNull(f.holder.ratePrompt.first())
        f.holder.removeRating(expected)
        runCurrent()
        assertEquals(listOf("rating/movie:1=null"), f.account.writes)
        assertEquals(listOf(a), f.writeSessions)
        assertEquals(LibraryWrite.Unrate, f.outcomes.single().write)
    }

    @Test fun staleSubmitDoesNotWriteOrCloseDifferentTitleReplacement() = runTest {
        val f = Fixture(this, a)
        val old = f.open(movie)
        val replacement = f.open(other)
        f.holder.submitRating(8.5, old)
        runCurrent()
        assertSame(replacement, f.holder.ratePrompt.first())
        f.assertUntouched()
        f.holder.submitRating(7.5, replacement)
        runCurrent()
        assertEquals(listOf("rating/movie:2=7.5"), f.account.writes)
    }

    @Test fun staleRemoveDoesNotWriteOrCloseDifferentTitleReplacement() = runTest {
        val f = Fixture(this, a)
        val old = f.open(movie)
        val replacement = f.open(other)
        f.holder.removeRating(old)
        runCurrent()
        assertSame(replacement, f.holder.ratePrompt.first())
        f.assertUntouched()
        f.holder.removeRating(replacement)
        runCurrent()
        assertEquals(listOf("rating/movie:2=null"), f.account.writes)
    }

    @Test fun valueEqualClosedAndReopenedPromptRejectsOldSubmit() = runTest {
        val f = Fixture(this, a)
        val old = f.open(movie)
        f.holder.closeRate()
        val replacement = f.open(movie)
        assertEquals(old, replacement)
        assertNotSame(old, replacement)
        f.holder.submitRating(8.5, old)
        runCurrent()
        assertSame(replacement, f.holder.ratePrompt.first())
        f.assertUntouched()
    }

    @Test fun valueEqualClosedAndReopenedPromptRejectsOldRemove() = runTest {
        val f = Fixture(this, a)
        val old = f.open(movie)
        f.holder.closeRate()
        val replacement = f.open(movie)
        assertEquals(old, replacement)
        assertNotSame(old, replacement)
        f.holder.removeRating(old)
        runCurrent()
        assertSame(replacement, f.holder.ratePrompt.first())
        f.assertUntouched()
    }

    @Test fun currentDismissClosesWithoutWriting() = runTest {
        val f = Fixture(this, a)
        val expected = f.open(movie)
        f.holder.closeRate(expected)
        assertNull(f.holder.ratePrompt.first())
        f.holder.closeRate(expected)
        runCurrent()
        f.assertUntouched()
    }

    @Test fun staleDismissDoesNotCloseDifferentTitleReplacement() = runTest {
        val f = Fixture(this, a)
        val old = f.open(movie)
        val replacement = f.open(other)
        f.holder.closeRate(old)
        runCurrent()
        assertSame(replacement, f.holder.ratePrompt.first())
        f.assertUntouched()
    }

    @Test fun valueEqualClosedAndReopenedPromptRejectsOldDismiss() = runTest {
        val f = Fixture(this, a)
        val old = f.open(movie)
        f.holder.closeRate()
        val replacement = f.open(movie)
        assertEquals(old, replacement)
        assertNotSame(old, replacement)
        f.holder.closeRate(old)
        runCurrent()
        assertSame(replacement, f.holder.ratePrompt.first())
        f.assertUntouched()
    }

    @Test fun equalCopyIsNotTheOwnedPromptForEitherCallback() = runTest {
        val f = Fixture(this, a)
        val expected = f.open(movie)
        f.holder.submitRating(8.5, expected.copy())
        f.holder.removeRating(expected.copy())
        runCurrent()
        assertSame(expected, f.holder.ratePrompt.first())
        f.assertUntouched()
    }

    @Test fun closedPromptCannotSubmitOrRemove() = runTest {
        val f = Fixture(this, a)
        val expected = f.open(movie)
        f.holder.closeRate()
        f.holder.submitRating(8.5, expected)
        f.holder.removeRating(expected)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
        f.assertUntouched()
    }

    @Test fun identityChangedCallbackDoesNotCloseOrWriteReplacement() = runTest {
        val f = Fixture(this, a)
        val old = f.open(movie)
        f.switchTo(b)
        val replacement = f.open(movie)
        f.holder.submitRating(8.5, old)
        f.holder.removeRating(old)
        runCurrent()
        assertSame(replacement, f.holder.ratePrompt.first())
        f.assertUntouched()
    }

    @Test fun submitQueuedBeforeActualIdentityChangeIsNotRetargeted() = runTest {
        val f = Fixture(this, a)
        val expected = f.open(movie)
        f.holder.submitRating(8.5, expected)
        f.actual.value = b // The holder has not received onIdentity yet.
        runCurrent()
        f.assertUntouched()
    }

    @Test fun removeQueuedBeforeActualIdentityChangeIsNotRetargeted() = runTest {
        val f = Fixture(this, a)
        val expected = f.open(movie)
        f.holder.removeRating(expected)
        f.actual.value = b
        runCurrent()
        f.assertUntouched()
    }

    @Test fun writeQueuedOnRepositoryLockKeepsPromptIdentityAndSuppressesLateOutcome() = runTest {
        val f = Fixture(this, a)
        val first = f.open(movie)
        val gate = CompletableDeferred<Unit>().also { f.account.writeGate = it }
        f.holder.submitRating(8.5, first)
        runCurrent()
        val second = f.open(movie)
        f.holder.removeRating(second)
        runCurrent()
        assertEquals(listOf(a), f.writeSessions)
        f.switchTo(b)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("rating/movie:1=8.5"), f.account.writes)
        assertEquals(listOf(a), f.writeSessions)
        assertTrue(f.outcomes.isEmpty())
    }

    @Test fun playerDeferralKeepsActualPromptAndInitialRating() = runTest {
        val f = Fixture(this, a)
        f.account.ratings[movie.ref] = 6.5
        f.repository.state(movie.ref).first { it.known }
        f.holder.setPlayerActive(true)
        f.holder.openRate(movie, a)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
        f.holder.setPlayerActive(false)
        val expected = f.holder.ratePrompt.first()!!
        assertEquals(RatePrompt(movie, 6.5, a), expected)
        f.holder.submitRating(8.5, expected)
        runCurrent()
        assertEquals(listOf(a), f.writeSessions)
        assertEquals(listOf("rating/movie:1=8.5"), f.account.writes)
    }

    @Test fun legacySubmitAndRemoveStillCaptureCurrentPrompt() = runTest {
        val f = Fixture(this, a)
        f.open(movie)
        f.holder.submitRating(7.5)
        runCurrent()
        f.open(other)
        f.holder.removeRating()
        runCurrent()
        f.holder.submitRating(8.5)
        f.holder.removeRating()
        runCurrent()
        assertEquals(listOf("rating/movie:1=7.5", "rating/movie:2=null"), f.account.writes)
        assertEquals(listOf(a, a), f.writeSessions)
        assertEquals(listOf(LibraryWrite.Rate(7.5), LibraryWrite.Unrate), f.outcomes.map { it.write })
    }

    @Test fun failedCapturedSubmitClosesReportsAndNeverReplaysOnRecovery() = runTest {
        val f = Fixture(this, null)
        val expected = f.open(movie)
        f.failGuestWrites = true
        f.holder.submitRating(8.5, expected)
        assertNull(f.holder.ratePrompt.first())
        runCurrent()
        assertEquals(1, f.failures.size)
        assertEquals(LibraryWriteResult.Failed(null), f.outcomes.single().result)
        f.failGuestWrites = false
        f.holder.retryStorage()
        f.holder.submitRating(8.5, expected)
        f.holder.removeRating(expected)
        runCurrent()
        assertTrue(f.rows.all().isEmpty())
        assertEquals(1, f.failures.size)
        assertEquals(1, f.outcomes.size)
    }

    @Test fun subscribedCollectorReceivesValueEqualReplacementAfterMissedClose() = runTest {
        val f = Fixture(this, a, backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val rendered = mutableListOf<RatePrompt?>()
        backgroundScope.launch { f.holder.ratePrompt.toList(rendered) }
        val old = f.open(movie)
        assertSame(old, rendered.last())

        backgroundScope.launch {
            // The UI finishes both operations before the subscribed collector can run.
            f.holder.closeRate()
            f.holder.openRate(movie)
            assertSame(old, rendered.last())
        }
        runCurrent()
        val current = f.holder.ratePrompt.first()!! // Independent snapshot, not the rendered instance.
        assertEquals(old, current)
        assertNotSame(old, current)
        assertSame(current, rendered.last())

        f.holder.submitRating(8.5, old)
        f.holder.removeRating(old)
        f.holder.closeRate(old)
        runCurrent()
        assertSame(current, rendered.last())
        assertSame(current, f.holder.ratePrompt.first())
        f.assertUntouched()
        f.holder.submitRating(7.5, rendered.last()!!)
        runCurrent()
        assertNull(rendered.last())
        assertEquals(listOf("rating/movie:1=7.5"), f.account.writes)

        f.holder.openRate(movie)
        runCurrent()
        f.holder.closeRate(rendered.last()!!)
        runCurrent()
        assertNull(rendered.last())
        assertEquals(1, f.account.writes.size)
    }

    @Test fun subscribedCollectorRevealsExactValueEqualReplacementAfterPlayerDeferral() = runTest {
        val f = Fixture(this, a, backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val rendered = mutableListOf<RatePrompt?>()
        backgroundScope.launch { f.holder.ratePrompt.toList(rendered) }
        val old = f.open(movie)
        assertSame(old, rendered.last())
        f.holder.setPlayerActive(true)
        runCurrent()
        assertNull(rendered.last())

        backgroundScope.launch {
            f.holder.closeRate()
            f.holder.openRate(movie)
            assertNull(rendered.last())
        }
        runCurrent()
        assertNull(rendered.last())
        f.holder.setPlayerActive(false)
        runCurrent()
        val current = f.holder.ratePrompt.first()!!
        assertEquals(old, current)
        assertNotSame(old, current)
        assertSame(current, rendered.last())

        f.holder.submitRating(8.5, old)
        f.holder.removeRating(old)
        f.holder.closeRate(old)
        runCurrent()
        assertSame(current, rendered.last())
        assertSame(current, f.holder.ratePrompt.first())
        f.assertUntouched()
        f.holder.removeRating(rendered.last()!!)
        runCurrent()
        assertNull(rendered.last())
        assertEquals(listOf("rating/movie:1=null"), f.account.writes)
    }

    private class Fixture(
        private val scope: TestScope,
        initial: AuthSession?,
        uiScope: CoroutineScope = scope.backgroundScope,
    ) {
        val actual = MutableStateFlow(initial)
        val account = FakeAccountLibrary()
        val rows = InMemoryGuestLibraryStore()
        val writeSessions = mutableListOf<AuthSession>()
        val outcomes = mutableListOf<LibraryWriteOutcome>()
        val failures = mutableListOf<StorageException>()
        var failGuestWrites = false
        private val guest = object : GuestLibraryStore by rows {
            override suspend fun upsert(row: GuestLibraryRow) {
                if (failGuestWrites) throw StorageException("test persistence unavailable")
                rows.upsert(row)
            }
        }
        private val source = object : AccountLibrarySource by account {
            override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
                writeSessions += session
                account.write(session, ref, kind, target)
            }
        }
        val repository = LibraryRepository(actual, GuestLibrarySource(guest), source)
        val holder = LibraryActionsStateHolder(repository, scope.backgroundScope, uiScope,
            { outcomes += it }, { failures += it }).also { it.onIdentity(initial) }

        suspend fun open(title: Title): RatePrompt {
            holder.openRate(title)
            scope.runCurrent()
            return holder.ratePrompt.first()!!
        }

        fun switchTo(session: AuthSession?) {
            actual.value = session
            holder.onIdentity(session)
        }

        suspend fun assertUntouched() {
            assertTrue(account.writes.isEmpty(), "no account mutation")
            assertTrue(rows.all().isEmpty(), "no guest mutation")
            assertTrue(outcomes.isEmpty(), "no superseded outcome")
            assertTrue(failures.isEmpty(), "no storage failure")
        }
    }
}
