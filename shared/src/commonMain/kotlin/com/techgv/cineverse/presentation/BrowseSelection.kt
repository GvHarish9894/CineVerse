package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.DiscoverSort
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.TvList
import com.techgv.cineverse.data.tmdb.canonicalGenreIds
import com.techgv.cineverse.data.tmdb.toHero
import com.techgv.cineverse.domain.MediaType

/** Endpoint provenance, not a display label. Discover never represents a curated list. */
sealed interface BrowseSelection {
    data class Movies(val list: MovieList) : BrowseSelection
    data class Tv(val list: TvList) : BrowseSelection
    data object People : BrowseSelection
    data class Trending(val window: TrendingWindow) : BrowseSelection

    /** TMDB AND genres, canonicalized and copied at the boundary; no curated-list or extra API parameters. */
    class Discover(
        val media: MediaType,
        genreIds: List<Int> = emptyList(),
        val sort: DiscoverSort = DiscoverSort.Popularity,
    ) : BrowseSelection {
        val genreIds: List<Int> = canonicalGenreIds(genreIds)
        override fun equals(other: Any?): Boolean = other is Discover &&
            media == other.media && genreIds == other.genreIds && sort == other.sort
        override fun hashCode(): Int = (media.hashCode() * 31 + genreIds.hashCode()) * 31 + sort.hashCode()
        override fun toString(): String = "Discover(media=$media, genreIds=$genreIds, sort=$sort)"
    }

    /** Only primitive screen scalars; safe for a route-backed StateSaver, never repository objects or credentials. */
    fun saveTo(saver: StateSaver) {
        val (source, value) = when (this) {
            is Movies -> "Movies" to list.name
            is Tv -> "Tv" to list.name
            People -> "People" to null
            is Trending -> "Trending" to window.name
            is Discover -> "Discover" to media.name
        }
        saver.putString(SOURCE, source)
        saver.putString(VALUE, value)
        saver.putString(GENRES, (this as? Discover)?.genreIds?.joinToString(","))
        saver.putString(SORT, (this as? Discover)?.sort?.name)
    }

    companion object {
        private const val SOURCE = "productBrowse.source"
        private const val VALUE = "productBrowse.value"
        private const val GENRES = "productBrowse.genres"
        private const val SORT = "productBrowse.sort"

        /** Missing selection returns null; malformed/unrecognized route scalars throw IllegalArgumentException. */
        fun restoreFrom(saver: StateSaver): BrowseSelection? {
            val source = saver.getString(SOURCE) ?: return null
            fun value() = requireNotNull(saver.getString(VALUE)) { "Missing Browse source value" }
            return when (source) {
                "Movies" -> Movies(MovieList.valueOf(value()))
                "Tv" -> Tv(TvList.valueOf(value()))
                "People" -> People
                "Trending" -> Trending(TrendingWindow.valueOf(value()))
                "Discover" -> Discover(
                    MediaType.valueOf(value()),
                    requireNotNull(saver.getString(GENRES)) { "Missing Browse genres" }
                        .let { if (it.isEmpty()) emptyList() else it.split(',').map(String::toInt) },
                    DiscoverSort.valueOf(requireNotNull(saver.getString(SORT)) { "Missing Browse sort" }),
                )
                else -> throw IllegalArgumentException("Unknown Browse source")
            }
        }

        /** Exact Home See-all handoff; the caller passes HomeUiState.trend for the Trending row. */
        fun fromHomeRow(row: HomeRow, trend: TrendingWindow): BrowseSelection = when (row) {
            HomeRow.Trending -> Trending(trend)
            HomeRow.PopularMovies -> Movies(MovieList.Popular)
            HomeRow.PopularTv -> Tv(TvList.Popular)
            HomeRow.NowPlaying -> Movies(MovieList.NowPlaying)
            HomeRow.Upcoming -> Movies(MovieList.Upcoming)
            HomeRow.PopularPeople -> People
            HomeRow.TopRatedMovies -> Movies(MovieList.TopRated)
        }
    }
}

internal fun BrowseSelection.pageSource(catalog: CatalogRepository): PageSource = when (this) {
    is BrowseSelection.Movies -> movieBrowseSource(list)(catalog)
    is BrowseSelection.Tv -> tvBrowseSource(list)(catalog)
    BrowseSelection.People -> browseTabs(BrowseKind.People).single().source(catalog)
    is BrowseSelection.Trending -> PageSource({ catalog.trending(window, it) }) { it.toHero() }
    is BrowseSelection.Discover -> PageSource({ catalog.discover(media, genreIds, sort, it) }) { it.toHero(media) }
}
