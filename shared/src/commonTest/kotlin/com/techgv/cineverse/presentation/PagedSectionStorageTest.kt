package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.toHero
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PagedSectionStorageTest {
    private val app = GenerationCounter()
    private val failure = StorageException("fixture")
    private val reports = mutableListOf<StorageException>()
    private var fail = false
    private val pages = mutableListOf<Int>()
    private fun source(label: String = "a") = PageSource({ page ->
        pages += page
        if (fail) throw failure
        PageDto(page, listOf(MediaItemDto(id = page, mediaType = "movie", title = "$label-$page")), 3)
    }) { it.toHero() }
    private fun PagedSection.content() = (state.value as SectionState.Content).value

    @Test fun initialStorageFailureIsGenericNotEmptyAndRetryRecovers() = runTest {
        val section = PagedSection(this, app, reports::add)
        fail = true
        section.load(source())
        advanceUntilIdle()
        assertEquals(SectionState.Error(SectionErrorKind.Generic), section.state.value)
        assertEquals(listOf(failure), reports)
        fail = false
        section.retryStorage()
        advanceUntilIdle()
        assertEquals(1, section.content().page)
        assertNull(section.content().moreError)
    }

    @Test fun failedNextPageRetainsItemsAndPageAndRetryAppendsOnce() = runTest {
        val section = PagedSection(this, app, reports::add)
        section.load(source())
        advanceUntilIdle()
        val before = section.content()
        fail = true
        section.loadMore()
        section.loadMore()
        advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Generic), section.content())
        section.retryStorage()
        advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Generic), section.content())
        fail = false
        section.retryStorage()
        section.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2, 2), pages)
        assertEquals(2, section.content().page)
        assertEquals(2, section.content().items.size)
        assertFalse(section.content().loadingMore)
        assertNull(section.content().moreError)
        section.retryStorage()
        advanceUntilIdle()
        assertEquals(4, pages.size)
    }

    @Test fun refreshStorageFailureAndRepeatedRetriesKeepTheLastRealPages() = runTest {
        val section = PagedSection(this, app, reports::add)
        section.load(source())
        advanceUntilIdle()
        section.loadMore()
        advanceUntilIdle()
        val before = section.content()
        fail = true
        section.reload(retainContent = true)
        assertEquals(before, section.content())
        advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Generic), section.content())
        section.retryStorage()
        advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Generic), section.content())
        fail = false
        section.retryStorage()
        advanceUntilIdle()
        assertEquals(1, section.content().page)
        assertNull(section.content().moreError)
    }

    @Test fun sourceAndAppSwitchesNeverResurrectRetainedContent() = runTest {
        val section = PagedSection(this, app, reports::add)
        section.load(source())
        advanceUntilIdle()
        fail = true
        section.load(source("new"))
        advanceUntilIdle()
        assertEquals(SectionState.Error(SectionErrorKind.Generic), section.state.value)
        fail = false
        section.retryStorage()
        advanceUntilIdle()
        app.bump()
        fail = true
        section.reload(retainContent = true)
        advanceUntilIdle()
        assertEquals(SectionState.Error(SectionErrorKind.Generic), section.state.value)
    }

    @Test fun canceledObsoleteSectionFailureCannotReportOrReplaceNewContent() = runTest {
        val gate = CompletableDeferred<Unit>()
        val section = PagedSection(this, app, reports::add)
        section.load(PageSource({ withContext(NonCancellable) { gate.await(); throw failure } }) { it.toHero() })
        runCurrent()
        section.load(source("new"))
        runCurrent()
        val before = section.state.value
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(before, section.state.value)
        assertTrue(reports.isEmpty())
    }

    @Test fun staleAppFailureReloadsWithoutReportingOrKeepingOldItems() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val section = PagedSection(this, app, reports::add)
        section.load(PageSource({ page ->
            if (first) { first = false; gate.await(); throw failure }
            PageDto(page, listOf(MediaItemDto(id = 9, mediaType = "movie", title = "new")), 1)
        }) { it.toHero() })
        runCurrent()
        app.bump()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, section.content().items.size)
        assertTrue(reports.isEmpty())
    }

    @Test fun disposedOwnerCannotReportALateNonCancellableFailure() = runTest {
        val owner = CoroutineScope(coroutineContext + Job())
        val gate = CompletableDeferred<Unit>()
        val section = PagedSection(owner, app, reports::add)
        section.load(PageSource({ withContext(NonCancellable) { gate.await(); throw failure } }) { it.toHero() })
        runCurrent()
        owner.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SectionState.Loading, section.state.value)
        assertTrue(reports.isEmpty())
    }

    @Test fun programmerErrorsAndCancellationAreNotStorageFailures() = runTest {
        val errors = mutableListOf<Throwable>()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> errors += e })
        try {
            val section = PagedSection(owner, app, reports::add)
            val bug = IllegalStateException("fixture bug")
            section.load(PageSource({ throw bug }) { it.toHero() })
            advanceUntilIdle()
            assertEquals(listOf(bug), errors)
            section.load(PageSource({ throw CancellationException("fixture cancel") }) { it.toHero() })
            advanceUntilIdle()
            assertEquals(listOf(bug), errors)
            assertTrue(reports.isEmpty())
            assertEquals(SectionState.Loading, section.state.value)
        } finally { owner.cancel() }
    }
}
