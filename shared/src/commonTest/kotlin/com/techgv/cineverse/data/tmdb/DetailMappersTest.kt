package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.CastCredit
import com.techgv.cineverse.domain.Fact
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.datetime.LocalDate

class DetailMappersTest {
    private fun dates(vararg countries: Pair<String, List<ReleaseDateDto>>) =
        ReleaseDatesDto(countries.map { (c, d) -> ReleaseDateCountryDto(c, d) })

    @Test
    fun `movie certification prefers theatrical then limited then any trimmed region before US`() {
        val d = dates(
            "IN" to listOf(ReleaseDateDto(" ", 3), ReleaseDateDto("A", 4), ReleaseDateDto(" UA ", 2)),
            "US" to listOf(ReleaseDateDto("PG-13", 3)),
        )
        assertEquals("UA", movieCertification(d, "IN"))
        assertEquals("PG-13", movieCertification(d, "GB"))
        assertEquals("A", movieCertification(dates("IN" to listOf(ReleaseDateDto("A", 4))), "IN"))
        assertNull(movieCertification(dates("IN" to listOf(ReleaseDateDto("", 3))), "IN"))
        assertNull(movieCertification(null, "IN"))
    }

    @Test
    fun `tv certification uses region else US blank region rating falls back`() {
        val r = ContentRatingsDto(listOf(ContentRatingDto("IN", " "), ContentRatingDto("US", "TV-MA"), ContentRatingDto("GB", "15")))
        assertEquals("15", tvCertification(r, "GB"))
        assertEquals("TV-MA", tvCertification(r, "IN"))
        assertNull(tvCertification(ContentRatingsDto(), "IN"))
    }

    @Test
    fun `tv runtime falls back to last episode omitted when unknown`() {
        assertEquals(50, tvRuntime(TvDetailDto(1, episodeRunTime = listOf(50, 45))))
        assertEquals(42, tvRuntime(TvDetailDto(1, lastEpisodeToAir = EpisodeDto(9, runtime = 42))))
        assertNull(tvRuntime(TvDetailDto(1, lastEpisodeToAir = EpisodeDto(9, runtime = null))))
        assertNull(tvRuntime(TvDetailDto(1, episodeRunTime = listOf(0))))
    }

    @Test
    fun `videos are YouTube only official Trailer then Trailer then Teaser then other`() {
        val videos = listOf(
            VideoDto(key = "clip", site = "YouTube", type = "Clip"),
            VideoDto(key = "vimeo", site = "Vimeo", type = "Trailer", official = true),
            VideoDto(key = "teaser", site = "YouTube", type = "Teaser"),
            VideoDto(key = "fanTrailer", site = "YouTube", type = "Trailer"),
            VideoDto(key = "official", site = "YouTube", type = "Trailer", official = true),
            VideoDto(key = "", site = "YouTube", type = "Trailer"),
        )
        assertEquals(listOf("official", "fanTrailer", "teaser", "clip"), orderedYouTubeVideos(videos).map { it.key })
    }

    @Test
    fun `review text is stripped of html and markdown entities decoded`() {
        val raw = "<p>Great <b>film</b>!</p><br/>**Bold** and *italic* with [a link](https://x.y) &amp; more &quot;quotes&quot;\r\n\r\n\r\n# Heading\n> quoted"
        assertEquals("Great film!\n\nBold and italic with a link & more \"quotes\"\n\nHeading\nquoted", stripMarkup(raw))
        assertEquals("snake_case stays", stripMarkup("snake_case stays"))
    }

    @Test
    fun `review avatar - gravatar url tmdb path nullable rating`() {
        val gravatar = ReviewDto("r1", "a", AuthorDetailsDto(avatarPath = "/https://secure.gravatar.com/avatar/x.jpg", rating = null), "x").toReview()
        assertEquals("https://secure.gravatar.com/avatar/x.jpg", gravatar.avatarUrl)
        assertNull(gravatar.avatarPath)
        assertNull(gravatar.rating)
        val tmdb = ReviewDto("r2", "author", AuthorDetailsDto(name = "", username = "u", avatarPath = "/abc.jpg", rating = 8.0), "x").toReview()
        assertEquals("/abc.jpg", tmdb.avatarPath)
        assertNull(tmdb.avatarUrl)
        assertEquals("author", tmdb.author)
        assertEquals(8.0, tmdb.rating!!, 0.0)
    }

    @Test
    fun `seasons put Specials last`() {
        val seasons = seasonsSpecialsLast(listOf(SeasonSummaryDto(10, 0, "Specials"), SeasonSummaryDto(12, 2), SeasonSummaryDto(11, 1)))
        assertEquals(listOf(1, 2, 0), seasons.map { it.seasonNumber })
        assertEquals("Specials", seasons.last().label)
    }

    @Test
    fun `movie detail - billing order top 10 director facts chips data`() {
        val cast = (0 until 12).map { CastDto(id = 100 + it, name = "c$it", character = "role$it", order = 11 - it) }
        val dto = MovieDetailDto(
            id = 550, title = "Fight Club", tagline = "  ", overview = "o", releaseDate = "1999-10-15", runtime = 139,
            status = "Released", originalLanguage = "en", genres = listOf(GenreDto(18, "Drama")),
            voteAverage = 8.4, voteCount = 100,
            credits = CreditsDto(cast = cast, crew = listOf(CrewDto(7, "Writer", job = "Screenplay"), CrewDto(8, "David", job = "Director"))),
            releaseDates = dates("US" to listOf(ReleaseDateDto("R", 3))),
            recommendations = PageDto(results = listOf(MediaItemDto(1, mediaType = "movie", title = "Rec"), MediaItemDto(2, mediaType = "person"))),
        )
        val detail = dto.toDetail("IN")
        assertEquals(10, detail.cast.size)
        assertEquals(listOf(111, 110), detail.cast.take(2).map { it.personId })
        assertEquals(CastCredit(8, "David", null, "Director"), detail.leadCrew)
        assertEquals("", detail.tagline)
        assertEquals("1999", detail.year)
        assertEquals("R", detail.certification)
        assertEquals(listOf(MediaRef(MediaType.Movie, 1)), detail.recommendations.map { it.ref })
        assertEquals(
            listOf(
                Fact("Status", "Released"), Fact("Release date", "Oct 15, 1999"), Fact("Original language", "English"),
                Fact("Runtime", "2h 19m"), Fact("Certification", "R"), Fact("Genres", "Drama"),
            ),
            detail.facts,
        )
    }

    @Test
    fun `tv detail - aggregate roles joined creator episodes fact network`() {
        val dto = TvDetailDto(
            id = 1396, name = "Breaking Bad", firstAirDate = "2008-01-20", status = "Ended", type = "Scripted", originalLanguage = "en",
            networks = listOf(NetworkDto(1, "AMC")), numberOfSeasons = 5, numberOfEpisodes = 62,
            createdBy = listOf(CreatorDto(66633, "Vince Gilligan")),
            aggregateCredits = AggregateCreditsDto(
                cast = listOf(
                    AggregateCastDto(2, "Aaron", roles = listOf(RoleDto("Jesse"), RoleDto(""), RoleDto("Narrator")), order = 1),
                    AggregateCastDto(1, "Bryan", roles = listOf(RoleDto("Walter White")), order = 0),
                ),
            ),
        )
        val detail = dto.toDetail("IN")
        assertEquals(listOf("Walter White", "Jesse / Narrator"), detail.cast.map { it.role })
        assertEquals("Creator", detail.leadCrew?.role)
        assertEquals("AMC", detail.network)
        assertEquals(Fact("Episodes", "5 seasons · 62"), detail.facts.first { it.label == "Episodes" })
        assertEquals(listOf("Status", "First air date", "Network", "Episodes", "Type", "Original language"), detail.facts.map { it.label })
    }

    @Test
    fun `D7 english fallback fills only blank fields and labels them`() {
        val localized = MovieDetailDto(1, title = "t", tagline = "", overview = "localized").toDetail("IN")
        val filled = localized.withEnglishFallback("English tagline", "English overview")
        assertEquals("English tagline", filled.tagline)
        assertTrue(filled.taglineInEnglish)
        assertEquals("localized", filled.overview)
        assertFalse(filled.overviewInEnglish)
        val emptyEnglish = localized.withEnglishFallback("", null)
        assertEquals("", emptyEnglish.tagline)
        assertFalse(emptyEnglish.taglineInEnglish)
    }

    private fun credit(id: Int, type: String?, votes: Int = 0, date: String? = null, character: String? = null, job: String? = null, department: String? = null, episodes: Int? = null) =
        PersonCreditDto(
            id = id, mediaType = type, title = "t$id", character = character, job = job, department = department,
            episodeCount = episodes, releaseDate = date, voteCount = votes,
        )

    @Test
    fun `person - credit count and known for dedupe by media type and id`() {
        val dto = PersonDetailDto(
            id = 1, name = "P", knownForDepartment = "Acting",
            combinedCredits = CombinedCreditsDto(
                cast = listOf(credit(5, "movie", votes = 10, character = "A"), credit(5, "tv", votes = 50, episodes = 3), credit(6, "movie", votes = 30)),
                crew = listOf(credit(5, "movie", votes = 10, job = "Producer", department = "Production"), credit(7, "person")),
            ),
        )
        val person = dto.toPersonDetail()
        assertEquals(3, person.creditCount)
        assertEquals(listOf(MediaRef(MediaType.Tv, 5), MediaRef(MediaType.Movie, 6), MediaRef(MediaType.Movie, 5)), person.knownFor.map { it.ref })
        assertEquals("Acting credits", person.creditsTitle)
    }

    @Test
    fun `person - known for keeps top 12 by vote count`() {
        val cast = (1..20).map { credit(it, "movie", votes = it) }
        val person = PersonDetailDto(1, combinedCredits = CombinedCreditsDto(cast = cast)).toPersonDetail()
        assertEquals((20 downTo 9).toList(), person.knownFor.map { it.ref.id })
    }

    @Test
    fun `person credits - date desc blank last sub-lines department row`() {
        val acting = personCredits(
            listOf(
                credit(1, "movie", date = "2001-01-01", character = "Hero"),
                credit(2, "tv", date = null, episodes = 4),
                credit(3, "movie", date = "2010-05-01", character = ""),
                credit(1, "movie", date = "2001-01-01", character = "Villain"),
            ),
        )
        assertEquals(listOf(3, 1, 2), acting.map { it.ref.id })
        assertEquals(listOf("", "as Hero / Villain", "4 episodes"), acting.map { it.role })

        val director = PersonDetailDto(
            id = 9, knownForDepartment = "Directing",
            combinedCredits = CombinedCreditsDto(
                cast = listOf(credit(1, "movie", character = "Cameo")),
                crew = listOf(credit(2, "movie", job = "Director", department = "Directing"), credit(3, "movie", job = "Writer", department = "Writing")),
            ),
        ).toPersonDetail()
        assertEquals("Directing credits", director.creditsTitle)
        assertEquals(listOf(2), director.credits.map { it.ref.id })
        assertEquals("Director", director.credits.single().role)
    }

    @Test
    fun `D7 biography fallback only when blank`() {
        val blank = PersonDetailDto(1, biography = " ").toPersonDetail()
        assertTrue(blank.withEnglishBiography("Bio").biographyInEnglish)
        assertEquals("Bio", blank.withEnglishBiography("Bio").biography)
        assertFalse(blank.withEnglishBiography("").biographyInEnglish)
        val localized = PersonDetailDto(1, biography = "Hindi bio").toPersonDetail()
        assertEquals("Hindi bio", localized.withEnglishBiography("Bio").biography)
    }

    @Test
    fun `season episodes - finale guest stars unaired`() {
        val season = SeasonDto(
            id = 1, seasonNumber = 2,
            episodes = listOf(
                EpisodeDto(2, episodeNumber = 2, airDate = "2099-01-01", episodeType = "finale", guestStars = listOf(CastDto(5, "G", "Guest"), CastDto(5, "G", "Guest"))),
                EpisodeDto(1, episodeNumber = 1, airDate = "2020-01-01", runtime = 0),
            ),
        ).toSeason(seriesId = 7)
        assertEquals(listOf(1, 2), season.episodes.map { it.episodeNumber })
        val today = LocalDate(2026, 10, 21)
        assertFalse(season.episodes[0].isUnaired(today))
        assertNull(season.episodes[0].runtimeMinutes)
        assertTrue(season.episodes[1].isUnaired(today))
        assertTrue(season.episodes[1].isFinale)
        assertEquals(1, season.episodes[1].guestStars.size)
    }

    @Test
    fun `language names are English display names with the code as fallback`() {
        assertEquals("Tamil", languageName("ta"))
        assertEquals("Hindi", languageName("hi"))
        assertEquals("xx", languageName("xx"))
        assertNull(languageName(" "))
    }
}
