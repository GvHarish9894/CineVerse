# TMDB API map

Every screen in the prototypes maps to TMDB v3 endpoints below. Base URL `https://api.themoviedb.org/3`.

## Auth and keys

- Get an API key / read access token at themoviedb.org → Settings → API. Prefer the **v4 read access token** sent as `Authorization: Bearer <token>`.
- **Never ship the token in a public web bundle.** Mobile and TV builds can read it from `local.properties` → `BuildConfig` (Android) or a build config (iOS), but a web (Wasm/JS) build exposes everything it contains, so the web app must call a small proxy you control (see the web kit).
- Attribution is required: show "This product uses the TMDB API but is not endorsed or certified by TMDB" plus the TMDB logo in About/Settings. Check TMDB's current terms of use before release.

## Global query params

| Param | From setting | Example |
|---|---|---|
| `language` | Content language | `en-US`, `hi-IN`, `ta-IN`, `ml-IN`, `es-ES` |
| `region` | Region | `IN`, `US`, `GB` (affects release dates, certifications, now playing) |
| `include_adult` | Include adult content | `false` by default |
| `page` | paging | 1..n (20 results/page) |

## Images

Call `GET /configuration` once at start-up and cache `images.secure_base_url` (normally `https://image.tmdb.org/t/p/`).

| Use | Size |
|---|---|
| Poster cards | `w342` (TV/web), `w185`–`w342` (phone) |
| Detail poster | `w500` |
| Backdrops / hero | `w1280` (TV, web), `w780` (phone) |
| Episode stills | `w300` |
| Profiles | `w185` cards, `h632` person page |

Fallback when a path is `null`: draw the gradient art the prototype uses (two colours from the item's palette). With real images, extract the two colours with Android Palette / a small k-means on the downscaled bitmap for the ambient glow.

## Screen → endpoint

| Screen | Endpoint(s) |
|---|---|
| Home hero + Trending | `/trending/all/{day\|week}` |
| Popular movies / TV | `/movie/popular`, `/tv/popular` |
| Now playing / Upcoming | `/movie/now_playing`, `/movie/upcoming` (pass `region`) |
| Top rated | `/movie/top_rated`, `/tv/top_rated` |
| Airing today / On TV | `/tv/airing_today`, `/tv/on_the_air` |
| Popular people | `/person/popular` |
| Latest trailers (web) | `/movie/upcoming` + `/movie/{id}/videos` (filter `site=YouTube`, `type=Trailer`) |
| Genres for filters | `/genre/movie/list`, `/genre/tv/list` |
| Filtered/sorted browse | `/discover/movie`, `/discover/tv` with `with_genres`, `sort_by=popularity.desc\|vote_average.desc\|primary_release_date.desc\|title.asc`, `vote_count.gte=100` for rating sort |
| Search | `/search/multi?query=` (results carry `media_type`) |
| Movie detail | `/movie/{id}?append_to_response=credits,videos,recommendations,reviews,release_dates,keywords` |
| TV detail | `/tv/{id}?append_to_response=credits,videos,recommendations,reviews,content_ratings,keywords` |
| Season / episodes | `/tv/{id}/season/{n}` |
| Person | `/person/{id}?append_to_response=combined_credits` |
| Certification | movie: `release_dates` → entry for your region; TV: `content_ratings` |

## Sign in (v3 session flow)

1. `GET /authentication/token/new` → `request_token`.
2. Send the user to `https://www.themoviedb.org/authenticate/{request_token}` (TV: show it as a QR code + short URL; phone/web: open the browser / new tab, add `?redirect_to=` your deep link or callback URL).
3. After approval, `POST /authentication/session/new` with `{ "request_token": "..." }` → `session_id`. Store it securely (EncryptedSharedPreferences / Keychain / http-only cookie on the proxy).
4. `GET /account` (with `session_id`) → `account_id`.
5. Sign out: `DELETE /authentication/session`.

Request tokens are valid for 60 minutes. TV: poll step 3 every few seconds while the QR screen is open, or keep the "I've approved it" button as in the prototype.

## Library (needs session)

| Action | Endpoint |
|---|---|
| Watchlist list | `GET /account/{account_id}/watchlist/movies` and `/watchlist/tv` |
| Favourites list | `GET /account/{account_id}/favorite/movies` and `/favorite/tv` |
| Rated list | `GET /account/{account_id}/rated/movies` and `/rated/tv` |
| Add/remove watchlist | `POST /account/{account_id}/watchlist` `{media_type, media_id, watchlist: true\|false}` |
| Add/remove favourite | `POST /account/{account_id}/favorite` `{media_type, media_id, favorite: true\|false}` |
| Rate | `POST /movie/{id}/rating` or `/tv/{id}/rating` `{ "value": 0.5..10 }` (0.5 steps) |
| Remove rating | `DELETE /movie/{id}/rating` |
| Per-title state | `GET /movie/{id}/account_states` (watchlist / favourite / rated) |

Guest mode (as in the prototypes): keep the library in a local database and offer to sync after sign-in.

## Data models (minimum fields used by the UI)

```kotlin
data class MediaSummary(val id: Int, val mediaType: MediaType, val title: String, val posterPath: String?, val backdropPath: String?,
                        val voteAverage: Double?, val date: String?, val genreIds: List<Int>)
data class MovieDetail(/* summary + */ val tagline: String?, val overview: String, val runtime: Int?, val status: String,
                       val genres: List<Genre>, val originalLanguage: String, val certification: String?,
                       val cast: List<CastMember>, val crew: List<CrewMember>, val videos: List<Video>,
                       val recommendations: List<MediaSummary>, val reviews: List<Review>, val keywords: List<String>)
data class TvDetail(/* + */ val numberOfSeasons: Int, val numberOfEpisodes: Int, val networks: List<String>, val type: String,
                    val seasons: List<SeasonSummary>, val createdBy: List<PersonRef>)
data class Episode(val episodeNumber: Int, val name: String, val overview: String, val airDate: String?, val runtime: Int?, val stillPath: String?, val voteAverage: Double?)
data class Person(val id: Int, val name: String, val knownForDepartment: String, val birthday: String?, val placeOfBirth: String?,
                  val biography: String, val profilePath: String?, val credits: List<Credit>)
```

User score ring: `pct = round(vote_average * 10)`; green ≥ 70, yellow ≥ 40, red below, grey "NR" when there are no votes.

## sample-tmdb.json

`sample-tmdb.json` in this kit holds every title, person and list from the prototype in TMDB's response shape. Use it for Compose previews, screenshot tests and offline development before the network layer exists.
