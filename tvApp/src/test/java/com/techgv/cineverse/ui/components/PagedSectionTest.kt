package com.techgv.cineverse.ui.components

import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.toHero
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §1.1 / §1.2: a section commits only results of its current generation and the current app generation. */
class PagedSectionTest {
    private val app = GenerationCounter()

    private fun dto(label: String, page: Int, totalPages: Int, ids: List<Int>) =
        PageDto(page, ids.map { MediaItemDto(id = it, mediaType = "movie", title = "$label-$it") }, totalPages)

    private fun source(label: String, totalPages: Int = 1, gate: CompletableDeferred<Unit>? = null, ids: (Int) -> List<Int> = { listOf(it * 10, it * 10 + 1) }) =
        PageSource({ page -> gate?.await(); dto(label, page, totalPages, ids(page)) }) { it.toHero() }

    private fun PagedSection.titles(): List<String> =
        (state.value as SectionState.Content).value.items.map { (it as Hero.OfTitle).title.title }

    // The test scope itself (not backgroundScope): advanceUntilIdle() does not run background work.
    private fun TestScope.section() = PagedSection(this, app)

    @Test fun `load replaces the list - an older source still in flight never commits`() = runTest {
        val section = section()
        val slow = CompletableDeferred<Unit>()
        section.load(source("old", gate = slow))
        advanceUntilIdle()
        section.load(source("new"))
        advanceUntilIdle()
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("new-10", "new-11"), section.titles())
    }

    @Test fun `loadMore appends the next page, drops repeated keys and stops after the last page`() = runTest {
        val section = section()
        section.load(source("a", totalPages = 2) { page -> if (page == 1) listOf(1, 2) else listOf(2, 3) })
        advanceUntilIdle()
        section.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a-1", "a-2", "a-3"), section.titles())
        val content = (section.state.value as SectionState.Content).value
        assertEquals(2, content.page)
        assertEquals(false, content.hasMore)
        section.loadMore() // after the last page: no-op
        advanceUntilIdle()
        assertEquals(2, (section.state.value as SectionState.Content).value.page)
    }

    @Test fun `failed next page keeps loaded items with a more-error`() = runTest {
        val section = section()
        var fail = false
        section.load(PageSource({ page -> if (fail) throw TmdbException(TmdbError.Network) else dto("a", page, 3, listOf(page)) }) { it.toHero() })
        advanceUntilIdle()
        fail = true
        section.loadMore()
        advanceUntilIdle()
        val content = (section.state.value as SectionState.Content).value
        assertEquals(SectionErrorKind.Offline, content.moreError)
        assertEquals(1, content.items.size)
    }

    @Test fun `result that missed a newer app generation is reloaded, not committed`() = runTest {
        val section = section()
        val gate = CompletableDeferred<Unit>()
        var label = "before"
        section.load(PageSource({ page -> gate.await(); dto(label, page, 1, listOf(1)) }) { it.toHero() })
        advanceUntilIdle()
        app.bump() // e.g. sign-in while page 1 is in flight
        label = "after"
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("after-1"), section.titles())
    }

    @Test fun `stale loadMore of a replaced source is dropped`() = runTest {
        val section = section()
        val page2 = CompletableDeferred<Unit>()
        section.load(PageSource({ page -> if (page == 2) page2.await(); dto("old", page, 2, listOf(page)) }) { it.toHero() })
        advanceUntilIdle()
        section.loadMore()
        advanceUntilIdle()
        section.load(source("new"))
        advanceUntilIdle()
        page2.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("new-10", "new-11"), section.titles())
        assertNull((section.state.value as SectionState.Content).value.moreError)
    }
}
