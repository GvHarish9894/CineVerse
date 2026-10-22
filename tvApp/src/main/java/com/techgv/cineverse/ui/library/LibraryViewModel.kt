package com.techgv.cineverse.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.LibraryStateHolder
import com.techgv.cineverse.presentation.LibraryTab
import com.techgv.cineverse.presentation.LibraryUiState
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import com.techgv.cineverse.ui.components.FocusMemory
import kotlinx.coroutines.flow.StateFlow

/** TV adapter over [LibraryStateHolder] (§7.8): focus memory; tabs switch on focus. */
class LibraryViewModel(
    handle: SavedStateHandle,
    repository: LibraryRepository,
) : ViewModel() {
    val memory = FocusMemory(handle)
    private val holder = LibraryStateHolder(repository, SavedStateHandleStateSaver(handle), viewModelScope)
    val state: StateFlow<LibraryUiState> = holder.state

    /** §7.8: tabs switch on focus; the previous tab's loads are dropped. */
    fun selectTab(t: LibraryTab) = holder.selectTab(t)

    /** "Revalidate on entry" (§7.8), see [LibraryStateHolder.revalidate]. */
    fun revalidate() = holder.revalidate()

    fun retry(media: MediaType) = holder.retry(media)

    fun loadMore(media: MediaType) = holder.loadMore(media)
}
