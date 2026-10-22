package com.techgv.cineverse.ui.library

import com.techgv.cineverse.data.library.LibraryEntry
import com.techgv.cineverse.data.library.title
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.SectionErrorKind
import com.techgv.cineverse.presentation.SectionState
import org.junit.Assert.assertEquals
import org.junit.Test
import com.techgv.cineverse.presentation.LibraryGroup
import com.techgv.cineverse.presentation.LibraryUiState

class LibraryGridItemsTest {
    private fun cards(vararg ids: Int, media: MediaType) = SectionState.Content(ids.map { LibraryEntry(title(it, media), null) })

    @Test
    fun `lazy keys include headers and status rows between the cards`() {
        val state = LibraryUiState(
            movies = LibraryGroup(cards(1, 2, media = MediaType.Movie), loadingMore = true, refreshError = SectionErrorKind.Offline),
            tv = LibraryGroup(cards(3, media = MediaType.Tv)),
        )
        assertEquals(
            listOf("header:movie", "refreshError:movie", "movie:1", "movie:2", "more:movie", "header:tv", "tv:3"),
            libraryGridItems(state).map { it.key },
        )
    }
}
