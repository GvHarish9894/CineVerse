# CineVerse for Android TV — Implementation Spec (v2)

Source of truth for UX: `cineverse-tv-prototype.html` (local only, git-ignored).
Data source: TMDB API v3/v4 — https://developer.themoviedb.org/docs/getting-started

v2 is a full rewrite after two design-review rounds. Every rule below is normative; where the prototype
and this document differ, this document wins and the difference is called out as **Δ prototype**.

---

## 0. Scope

| Screen | Purpose |
|---|---|
| Splash | Logo + progress bar, then Login or Home |
| Login | TMDB sign-in via QR / link, "I've approved it", "Continue as guest" |
| Home | Hero stage + rows: Trending (Today / This week), Popular movies, Popular TV, Now playing, Upcoming, Popular people, Top rated movies |
| Movies / TV / People | Browse page, tab chips + 7-column grid |
| Detail (movie / TV) | Hero, action buttons, rows: Seasons (TV), Top billed cast, Videos, Recommendations, Reviews, Details |
| Person | Photo, department, born / place, biography (expand), Known for, Credits |
| Season | Seasons list + episodes list (metadata only) |
| Search | On-screen keyboard + live multi-search |
| My Library | Watchlist / Favourites / Rated |
| Settings | Rows + live description pane |
| Player | YouTube trailer playback (in-app) |
| Modals | Rate, Exit, Import-library (new) |
| Toasts, Feedback | status toasts; haptics / sounds / glow / reduce-motion |

**Out of scope for v1**: on-screen remote, interaction HUD, sample data, episode playback (TMDB has none),
player captions toggle (not exposed by the chosen player, see §7.10), watch-provider info.

**Platform**: Compose + `androidx.tv.material3`, Kotlin 2.2, AGP 9, D-pad only.
`minSdk` — see decision D8.

---

## 1. Architecture

Single module `app`, package `com.techgv.cineverse`. Manual DI (`AppContainer` built in `Application`),
no Hilt. ViewModels get `SavedStateHandle` via `viewModel(factory = …)`; UI collects with
`collectAsStateWithLifecycle()`.

```
com.techgv.cineverse
├─ CineVerseApp.kt / MainActivity.kt
├─ data/
│  ├─ tmdb/   TmdbApi (Retrofit), Dtos, TmdbClient (OkHttp + token selection), TmdbRepository (+cache)
│  ├─ auth/   AuthRepository, CredentialStore (Keystore-encrypted DataStore)
│  ├─ library/ LibraryRepository (account or Room), ImportLedger
│  └─ settings/ SettingsRepository (DataStore)
├─ domain/    Title, Person, MediaRef(mediaType,id), Season, Episode, Review, Video, Credit, Hero = Title|Person
├─ ui/
│  ├─ theme/, components/, nav/ (routes, AppNav, NavRail)
│  ├─ splash/ login/ home/ browse/ detail/ person/ season/ search/ library/ settings/ player/
│  └─ feedback/  Feedback (haptics, sounds, motion) via CompositionLocal
└─ util/
```

### 1.1 State
One `data class XxxUiState` per screen in a `StateFlow`. Loading / error / content live inside the state
per *section* (a failed row never blanks a screen). Every async result is tagged with a **request
generation**: a monotonically increasing `Long` from a per-scope counter (app-wide for auth/settings
changes, per screen for tab / season / search / page loads), bumped on every replacement or cancellation.
The settings tuple alone is not enough (A→B→A, or sign-out→sign-in to the same account, would match
again). Every UI or cache commit checks `generation == current` first.

### 1.2 Navigation (Navigation Compose, typed routes)
Routes carry only identifiers:
`Home`, `Browse(kind)`, `Search`, `Library`, `Settings`, `Login`, `Detail(mediaType,id)`,
`Person(id)`, `Season(seriesId, seasonNumber)`, `Player(videoKey, title, kicker)`.

| Prototype mode | Implementation |
|---|---|
| `push` (title, person, season, player) | `navigate(route)` |
| `pop` | `popBackStack()` |
| `reset` (rail item, splash → root, exit → splash) | `navigate(route) { popUpTo(graph.id) { inclusive = true }; launchSingleTop = true }` — the new root is the only entry. Login can never remain under Home. |
| rail press on current root | no navigation; move focus from rail into content |

**BACK precedence** (prototype order, one Δ at step 2)
1. modal open → close it, focus returns to the invoking control
2. on Login: cold-start Login → Exit dialog; Login pushed from Settings / Library → pop (**Δ prototype**)
3. focus in rail → move focus to content
4. back stack has a pushed route → pop (origin restores focus, §1.3)
5. on a non-Home root → reset to Home
6. on Home with focus not at row 0 / col 0 → focus (0,0)
7. on Home at (0,0) → Exit dialog

Never intercept the system HOME key.

**Rail visibility**: Home, Browse, Search, Library, Settings only. Detail, Person, Season, Player, Login
have no rail (prototype `withNav` absent there). On rail screens, LEFT from column 0 of the content opens
the rail. Exceptions: Search — LEFT from results column 0 goes to the keyboard (remembered key), LEFT from
keyboard column 0 goes to the rail; Season — LEFT from episodes goes to the seasons list (no rail).

**Generations** (§1.1) are checked at two levels: the app-wide counter (auth / settings) **and** the
owning scope's counter. Scopes are independent: Library's movie stream and TV stream, each Browse tab,
each Season, the Search query — a new movie-page request never invalidates a concurrent TV-page response.

### 1.3 Focus memory
Per back-stack entry, in `SavedStateHandle`:
`focus = (panelKey, rowKey, itemKey)` + lazy-list scroll state, plus screen scalars (query, tab, trend,
selected season, rating draft). `rowKey` is the row's stable id (`"popularMovies"`, `"season:3"`),
`itemKey` is `MediaRef` / person id / key id — never an index.
Also saved: the item's last known index in its row. Restore: wait for data of the current generation →
scroll row into view → await composition → `requestFocus()` on the item key → if missing, the item at
`min(savedIndex, lastIndex)` → if the row is missing, first item of the first row. Dialogs restore focus to
their invoker on close. Tested across process death. Routes carry only ids, so a screen restored after
process death reloads its own data (e.g. Season reloads the TV detail for the seasons list, §7.6).

### 1.4 Lists
Foundation `LazyRow` / `LazyVerticalGrid` (tv-foundation `TvLazy*` / `pivotOffsets` are deprecated) with a
`bringIntoViewSpec` that pins the focused card to the row's left edge (prototype `translateX(-tx)`).

### 1.5 Images
Coil 3. Base URL from `/3/configuration` when available, else the constant
`https://image.tmdb.org/t/p/`. Sizes: poster `w342` card / `w780` hero, backdrop `w1280`, profile `w185`
card / `h632` hero, still `w300`. All `*_path` fields are nullable → fallback card: gradient from a hash of
the title + text (titles) or initials (people). The image `OkHttpClient` carries **no** auth header.

### 1.6 Dependencies to add

| Lib | Why |
|---|---|
| retrofit + kotlinx-serialization converter, okhttp (+ logging-interceptor, debug only) | TMDB |
| kotlinx-serialization-json + plugin | DTOs, routes |
| navigation-compose, lifecycle-viewmodel-compose, lifecycle-runtime-compose | nav / VM / lifecycle collect |
| coil3 compose + network-okhttp | images |
| datastore-preferences | settings, credentials |
| room runtime/ktx + KSP | guest library, import ledger |
| `com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0` (pinned) | Player |
| palette-ktx | glow colour |
| zxing core | login QR |

---

## 2. Credentials & configuration

`secrets.properties` (git-ignored):
```
TMDB_READ_ACCESS_TOKEN=   # v4 "API Read Access Token"
```
`tvApp/build.gradle.kts` reads it into `BuildConfig.TMDB_READ_ACCESS_TOKEN` (`buildFeatures.buildConfig = true`).
The read token is extractable from the APK; this is accepted client-key risk (rotate if abused; a backend
proxy is the only real fix and is out of scope).

Manifest (Phase 1): `INTERNET`; `MainActivity` exported with `MAIN` + `LAUNCHER` + `LEANBACK_LAUNCHER`;
`uses-feature android.software.leanback required=false`, `android.hardware.touchscreen required=false`;
`android:banner` 320×180 (localized), icon. Backup: `android:dataExtractionRules` (API 31+) **and**
`android:fullBackupContent` (API < 31, needed once D8 lowers minSdk) both exclude the credential DataStore
file; `allowBackup` stays true for settings/guest library only.

---

## 3. TMDB client

### 3.1 Token selection (per call, explicit)
| Call family | Bearer | Extra |
|---|---|---|
| catalog (lists, details, search, config, genres) | read token | — |
| `/4/auth/request_token`, `/4/auth/access_token` | read token | — |
| `POST /3/authentication/session/convert/4` body `{"access_token": userAccessToken}`; `DELETE /3/authentication/session` body `{"session_id": sessionId}` | read token | JSON body, no query auth |
| v3 account reads/writes (`/3/account/{id}`, `/watchlist`, `/favorite`, `/rating`, `/account_states`) | read token | `session_id` query |
| `/4/account/{account_object_id}/...` reads, `DELETE /4/auth/access_token` | **user access token** | — |

Interceptor attaches a bearer **only** for host `api.themoviedb.org`; the API interface marks each call
with the token kind.

### 3.2 Parameters (per endpoint, no blanket rules)
- `language`: catalog lists, details, search, credits, videos, reviews, recommendations, season, person,
  v4 account lists. Not on config, auth, account_states, writes, release_dates, content_ratings.
- `region`: `movie/now_playing`, `movie/upcoming`, `movie/popular`, `movie/top_rated`; used client-side to pick certification.
- `timezone` (= `TimeZone.getDefault().id`): `tv/airing_today`, `tv/on_the_air` only.
- `include_adult`: `search/multi` only.
- `page`: all paged lists; stop at `min(total_pages, 500)`.

### 3.3 Error classification (by operation + TMDB `status_code`, never by HTTP status alone)
Codes from https://developer.themoviedb.org/docs/errors.

| Operation | TMDB `status_code` | Meaning | Handling |
|---|---|---|---|
| `POST /4/auth/access_token` (polling) | 41 (HTTP 422) | not approved yet | pending — keep polling until timeout |
| same | 33 | request token expired/invalid | "Code expired — press OK for a new one" → regenerate |
| same | 7 / 10 | invalid / suspended app key | app-credential error banner; stop polling |
| same | HTTP 429 | throttled | wait `Retry-After` (min 3 s), keep polling within the 2-min deadline — checked **before** the terminal row |
| same | any other 4xx (incl. 17, 35, or an unparsable body) | exchange rejected / denied / cancelled by user | **terminal**: stop polling, "TMDB didn't approve this sign-in", button "Get a new code" → regenerate |
| same | network / 5xx | unknown | retry next tick; after timeout show "Check again" |
| any call | 7 / 10 | app key problem (even on session-bearing calls) | misconfiguration banner; **never** signs the user out |
| call with `session_id` | 3, 14, 17, 37 | session invalid / denied / not found | local sign-out, Login with "Sign-in expired" |
| call with user token | 35, 36 | token invalid / no write permission | local sign-out, same message |
| any | HTTP 429 | rate limited | one retry after `Retry-After`, then inline error |
| any | 34 | not found | section "Not available" |
| any | network / 5xx | — | per-section error with focusable "Try again" |

Outside the exchange, a bare HTTP 401 without a parsable body is treated as the app-credential case (safe
default: no forced sign-out). Only code 41 ever keeps the spinner. The real rejection payload for a denied
approval is captured in the Phase-1 auth spike and the table amended if TMDB uses a different code.

### 3.4 Cache
Key `(endpoint, ids, language, region, timezone, include_adult, page)`; account data additionally keyed
by `account_object_id`. In-memory LRU; TTL lists 10 min, details 1 h, config/genres process lifetime.
Invalidated by: writes (affected title + Library lists), settings change (then **visible screens
reload**), sign-in / sign-out (account entries cleared, in-flight account work cancelled by generation).
No disk cache for v1. Offline: show per-section offline state (distinct from empty) with retry.

### 3.5 Endpoint map
| Data | Endpoint |
|---|---|
| Image config | `GET /3/configuration` |
| Genres | `GET /3/genre/movie/list`, `/3/genre/tv/list` |
| Trending | `GET /3/trending/all/{day\|week}` |
| Home / Movies tabs | `/3/movie/popular`, `/now_playing`, `/upcoming`, `/top_rated` |
| Home / TV tabs | `/3/tv/popular`, `/airing_today`, `/on_the_air`, `/top_rated` |
| People | `/3/person/popular` |
| Movie detail | `GET /3/movie/{id}?append_to_response=credits,videos,recommendations,reviews,release_dates[,account_states]` |
| TV detail | `GET /3/tv/{id}?append_to_response=aggregate_credits,videos,recommendations,reviews,content_ratings[,account_states]` |
| Videos only (Home Play) | `GET /3/{movie\|tv}/{id}/videos` |
| Season | `GET /3/tv/{id}/season/{n}` |
| Person | `GET /3/person/{id}?append_to_response=combined_credits` |
| Search | `GET /3/search/multi` |
| Account | `GET /3/account?session_id=` (no id needed; response `id` is the v3 `account_id`) |
| Button states | `GET /3/{movie\|tv}/{id}/account_states?session_id=` |
| Library reads | `GET /4/account/{account_object_id}/{movie\|tv}/{watchlist\|favorites\|rated}?sort_by=created_at.desc&page=` |
| Writes | `POST /3/account/{account_id}/watchlist`, `/favorite`; `POST`/`DELETE /3/{movie\|tv}/{id}/rating` |
| Auth | `POST /4/auth/request_token`, `POST /4/auth/access_token`, `POST /3/authentication/session/convert/4`, `DELETE /4/auth/access_token`, `DELETE /3/authentication/session` |

Identity types are distinct in code: `AccountObjectId(String)` (v4), `AccountId(Int)` (v3),
`SessionId`, `UserAccessToken`, `ReadToken`.

---

## 4. Authentication

### 4.1 Sign-in
1. `POST /4/auth/request_token` `{ "redirect_to": "https://www.themoviedb.org/" }`.
2. Show QR + text of `https://www.themoviedb.org/auth/access?request_token={token}`.
   The short-lived request token is the **only** credential ever rendered on screen; it is never logged.
3. "I've approved it" starts polling `POST /4/auth/access_token` `{request_token}` every 3 s for 2 min
   (single-flight). Pending failures keep the spinner ("Waiting for TMDB to confirm…"). Timeout → "Still
   waiting — press OK to check again". Polling is cancelled on BACK, "Continue as guest", screen dispose
   and app background.
4. Success → `POST /3/authentication/session/convert/4` `{access_token}` → `session_id` →
   `GET /3/account?session_id=` → numeric `id`, `username`, `name`. If any step after the access token fails,
   retry *that step* (the request token is consumed and cannot be exchanged again). If the attempt cannot
   complete, discard it and restart login; nothing partial is persisted.
5. Commit the tuple `{userAccessToken, accountObjectId, sessionId, accountId, username, name}`
   **atomically**. `userAccessToken` and `sessionId` are encrypted with an Android-Keystore AES key before
   DataStore. Key loss → treated as signed out.
6. Toast "Signed in as {name ?: username}". If a guest library exists → Import dialog (§8.3).

### 4.2 Sign-out
Snapshot credentials → immediately mark signed out, bump account generation, clear account caches, clear
the tuple → then best-effort bounded (5 s) `DELETE /4/auth/access_token` and
`DELETE /3/authentication/session`. Remote failure is logged (without token values) and ignored.
Toast "Signed out · Your library stays on this TV".

### 4.3 Logging policy
Release: no HTTP logging. Debug: redact `Authorization`, request/response bodies of auth endpoints, and
`session_id` query values. Tokens never appear in analytics, crash breadcrumbs or toasts.

---

## 5. Shell: rail, clock, ambient backdrop
- Rail items in prototype order: Search, Home, Movies, TV Shows, People, My Library, Settings. Collapsed
  icons; expands with labels while focused. Bottom: initials + name / "Guest" (D3: initials only).
- Clock top-right, `HH:mm`, 15 s tick.
- Ambient backdrop: blurred artwork of hero / focused item, palette colour; cross-fade 400 ms (0 when
  Reduce motion); hidden when Ambient glow is off.

---

## 6. Shared components

**Units.** All dp values in this document are **1280×720 design units** taken from the prototype canvas.
A 1080p TV at 320 dpi is only 960 dp wide, so `ui/components/DesignCanvas.kt` overrides `LocalDensity`
once in `MainActivity` with `density = windowWidthPx / 1280f` (user `fontScale` preserved, real window
width so 720p and 4K scale too). Code uses spec numbers verbatim. Every Compose `Dialog`/`Popup` starts a
new composition root and must wrap its content in `DesignCanvas { }`.

**Cross-lane contracts (pinned):** `domain/MediaRef(mediaType, id)`; `domain/LibraryActions`
(`state(ref): Flow<LibraryState>`, `toggleWatchlist(title)`, `toggleFavourite(title)`, `openRate(title)`)
exposed via `LocalLibraryActions`; `LibraryState(known, watchlist, favourite, rating?, pending: Set<LibraryKind>)`
with `Unknown` (buttons disabled while `known == false`); `ui/components/Toasts.kt` (`Toaster.show(title, subtitle?)`,
`LocalToaster`, `ToastHost` installed in `AppNav`); the import-library dialog is hosted in `AppNav` by the Library lane.

| Component | Size dp (step) | Content |
|---|---|---|
| PosterCard | 140×210 (156) | poster, title, "year · ★ 7.8" / "Coming {date}" / "Unrated", TV tag, badge |
| LandscapeCard | 248×140 (264) | backdrop, title, subtitle (network name only when known) |
| PersonCard | 132 circle (148) | profile / initials, name, role |
| SeasonCard | 158×240 (174) | poster, "Season n" / "Specials", "{episode_count} episodes · {year}" |
| ReviewCard | 368×180 (384) | author, ★ rating or none, plain-text excerpt |
| FactCard | 204×100 (220) | label + value |
| CreditCard | 308×110 (324) | year or "—", title, "as {character}" / "{job}" / "{n} episodes" |
| Chip | h 44 | tab / filter |
| ActionButton | h 56 | icon + label; primary / solid / on |
| ScoreRing | 48 | `vote_average×10`; green ≥70, yellow ≥40, red; hidden when `vote_count == 0` |

Focus: scale 1.08 + palette-colour glow, 150 ms. **Reduce motion** → scale snap, no Coil crossfade, no
scroll animation, no backdrop cross-fade. **Ambient glow off** → no coloured glow, plain focus border
remains (focus is always visible). "Coming {date}" only when the release date is in the future; otherwise
an unrated title shows "Unrated".

---

## 7. Screens

### 7.1 Splash
Starts `/configuration`, genres and credential load in parallel. Navigates after **2.3 s regardless of
network** (reset to Home if credentials present, else Login). Missing config → constant image base URL;
missing genres → chips omitted until loaded.

### 7.2 Home
`HomeUiState(trend, hero: Hero?, rows: List<RowState>)` where `Hero = Title | Person` and each
`RowState` has its own loading/error. Hero follows focus (120 ms debounce); default = first trending item.
- Title hero: eyebrow "Movie · #3 trending this week" (rank only within the trending row), ring, meta,
  overview; buttons Play trailer / Watchlist / More info.
- Person hero (D5): eyebrow `known_for_department`, overview "Known for A, B, C" from `known_for[]`;
  button View profile. No extra fetch.
- **Play trailer from Home**: list items have no video key → fetch `/videos` on press (cached), states
  loading / "No trailer available" / error. Hero button is disabled while loading.
- Popular TV landscape subtitle: first genre only (network unknown from list data).

### 7.3 Browse
Tabs per prototype `defs`; chip selection changes **on focus**; OK on a chip moves focus into the grid.
`LazyVerticalGrid`, 7 columns, paginate when last row visible. Tab change cancels the previous tab's load
(generation). Tab + scroll + focus saved per route.

### 7.4 Detail
One request (§3.5) plus `account_states` when signed in (appended or separate; verify append works in
Phase 3, else separate call).
Hero: poster, type label, title (46 sp if > 17 chars else 60), tagline (hidden if blank), chips
[year, runtime, certification, genres…], ring, overview.
- Runtime: movie `runtime`; TV `episode_run_time.firstOrNull() ?: last_episode_to_air?.runtime`; omit if null.
- Certification: movie `release_dates` for `region` → first non-blank `certification` preferring type 3,
  then 2, then any; fallback `US`; `trim()`. TV `content_ratings` for region, fallback `US`. Omit if none.
Buttons: Play trailer (hidden when no YouTube video), Watchlist, Favourite, Rate ("Rated 7.5"),
Episodes (TV). Button state source: guest → Room; signed in → `account_states`.
Rows: Seasons (TV; Specials last), Top billed cast (10) + Director (`crew.job == "Director"`) or Creator
(`created_by[0]`); TV role = `roles.joinToString(" / ") { character }`; Videos (YouTube only; order
official Trailer → Trailer → Teaser → other; if empty for a non-`en-US` language, refetch `en-US`);
Recommendations; Reviews (hidden if empty; strip HTML/markdown; nullable rating; avatar may be a
`/https://…gravatar` string → handle); Details facts (movie: Status, Release date, Original language,
Runtime, Certification, Genres; TV: Status, First air date, Network, Episodes "3 seasons · 24", Type,
Original language).
After any mutation: refresh `account_states` and invalidate Library lists.

### 7.5 Person
`/3/person/{id}?append_to_response=combined_credits`. Chips: "Born {date}" (+ "– {deathday}"), place,
"{n} credits" where n = unique `(media_type,id)` across cast+crew. Biography clamped to 4 lines with
expand button; blank → "No biography available", no button.
Known for: cast ∪ crew deduped by `(media_type,id)`, sorted by `vote_count` desc, top 12.
Credits row title: "Acting credits" if `known_for_department == "Acting"`, else "{department} credits";
items sorted by date desc with blank dates last; sub-line `as {character}` / `{job}` / "{episode_count} episodes".

### 7.6 Season
Left: seasons from the TV detail's `seasons[]` (Specials last). The Season screen loads the TV detail
itself (cache hit when coming from Detail; network after process death or deep route) — it never depends on
Detail's in-memory state. Selected season comes from the route / saved state; if that season no longer
exists, select the first and toast "Season not available". Right: episodes of the focused season
(`/3/tv/{id}/season/{n}`, cached, previous load cancelled by generation). Episode card: still, "E{n} · {name}",
air date, runtime, ★ rating, finale badge, 2-line overview. Focus on a season resets episode selection
to the first episode; OK on a season moves focus to episodes. **OK on an episode opens an inline
episode panel** (full overview, guest stars); BACK closes the panel and returns focus to that episode.
Edge cases: no seasons → Episodes button hidden; specials-only → list shows Specials; unaired episodes
show "Airs {date}" and no rating.

### 7.7 Search (**matches prototype, previous v1 text superseded**)
Left: query field + 6-column keyboard (A–Z, 0–9, Space, Delete, Clear; 28-char cap). Hardware keyboard
and `KEYCODE_DEL` also edit the query. Empty query → "Trending now" suggestions (trending/all week).
≥ 1 character → `search/multi` after 300 ms debounce; stale responses dropped. Results: one mixed grid in
API order (titles as PosterCard, people as PersonCard), paginated. Empty → "Nothing on TMDB matches that
yet…". Query and results focus survive navigation; after Clear, focus returns to the first result or
the keyboard.

### 7.8 My Library
Tabs Watchlist / Favourites / Rated switch on focus; OK enters the grid or CTA.
Signed in: for the tab, load movie and TV lists (user token, `created_at.desc`) as **two groups
"Movies" / "TV Shows"**, each independently paged, each with its own loading/error (one failing group
never makes the library look empty). **Δ prototype**: no single merged recency order (API gives no
per-item timestamp for watchlist/favorites). Rated cards badge `account_rating.value`.
Guest: Room, newest first. Revalidate on entry. Empty states and CTAs per prototype.

### 7.9 Settings
| Row | Type | Key / effect |
|---|---|---|
| Haptic feedback | toggle | `haptics` (default **on**) |
| Haptic strength | Light / Medium / Strong, ←→ | `strength` 6 / 12 / 20 ms (default Medium) |
| Navigation sounds | toggle | `sounds` (default off) |
| Ambient glow | toggle | `glow` (default on) |
| Reduce motion | toggle | `motion` (default off) |
| Content language | en-US, hi-IN, ta-IN, ml-IN, es-ES | `language` (default en-US) → cache invalidate + reload |
| Region | IN, US, GB | `region` (default IN; description: release dates & certifications — **Δ prototype**: no watch-provider promise) |
| Include adult content | toggle | `include_adult` (default off; search only) |
| Sign in / Sign out | action | §4 |
| About | static | version, TMDB logo, "This product uses the TMDB API but is not endorsed or certified by TMDB." |

On first sign-in, if the account's `iso_639_1` / `iso_3166_1` / `include_adult` differ from current
settings, show a focusable row at the top of Settings — "Use your TMDB account defaults ({lang}, {region})"
— until applied or dismissed. Never a toast action (toasts must not take focus).

### 7.10 Player (D1 decided)
**Library**: `com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0` (wraps the YouTube IFrame
Player API in a WebView; Google's native YouTube Android SDK is deprecated).

**Layout — no overlays (YouTube Required Minimum Functionality forbids anything drawn in front of the
embedded player)**. **Δ prototype**: the prototype's auto-hiding overlay is replaced by a fixed layout:
- `BoxWithConstraints`: reserve the measured height of the control strip + TV safe-area insets, then fit
  the largest 16:9 `YouTubePlayerView` into the remaining width/height, centred. No hard-coded pixels.
- Nothing is drawn inside the player bounds: no focus scale/shadow bleed from the strip (clip the strip),
  no toast, no dialog dim layer, no scrim. Toasts and dialogs are laid out in the strip region.
- Control strip (owns focus): kicker, title, progress bar, buttons −10 s / Play-Pause / +10 s /
  **Open in YouTube** (always present, so a non-touch viewer can reach captions and YouTube's own controls).
- Source review covers the overlay rule only; branding, ads and client-identity aspects of YouTube's
  Required Minimum Functionality are checked on device in the Phase-1 spike.

**Wiring**
```kotlin
val view = YouTubePlayerView(context).apply { enableAutomaticInitialization = false }
val options = IFramePlayerOptions.Builder(context).controls(0).rel(0).ivLoadPolicy(3).build()
// Create AND initialize inside AndroidView(factory = { ... }) so it runs exactly once per Player route.
// The View/WebView is never held by a ViewModel (it retains the Activity Context); the ViewModel holds
// scalars only (position, state, generation). The `loaded` flag belongs to the View instance (reset
// whenever the factory creates a new View after Activity recreation); position is restored separately.
// startIfNeeded() is a no-op until onReady has stored a player. Lifecycle test includes Activity recreation.
view.initialize(object : AbstractYouTubePlayerListener() {
    override fun onReady(p: YouTubePlayer) {
        if (gen != currentGen) return                     // obsolete route instance: ignore entirely
        player = p; controlsEnabled = true               // always keep the ready player
        if (isResumed) startIfNeeded()                   // else deferred to ON_RESUME (see below)
    }
    override fun onCurrentSecond(p: YouTubePlayer, s: Float) { position = s }
    override fun onVideoDuration(p: YouTubePlayer, d: Float) { duration = d }
    override fun onStateChange(p: YouTubePlayer, st: PlayerConstants.PlayerState) { state = st }
    override fun onError(p: YouTubePlayer, e: PlayerConstants.PlayerError) { onPlayerError(e) }
}, options)
```
- Controls disabled until `onReady`; seek clamped to `[0, duration]`; `ENDED` shows "Play again".
- `onError`: `VIDEO_NOT_FOUND`, `VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER`, `INVALID_PARAMETER_IN_REQUEST`,
  `HTML_5_PLAYER`, `REQUEST_MISSING_HTTP_REFERER`, `UNKNOWN` → inline message "This trailer can't be
  played here" with buttons **Open in YouTube** (`Intent(ACTION_VIEW, "vnd.youtube:$key")`, catch
  `ActivityNotFoundException`) and **Back**. Never alter the library's origin/referer handling.
- Lifecycle: `lifecycle.addObserver(view)` (13.0.0 reacts at `ON_STOP`, not `ON_PAUSE`); call `pause()`
  explicitly in `ON_PAUSE`. `ON_RESUME` → `startIfNeeded()`: `loadVideo` once if not yet loaded (covers
  `onReady` firing while backgrounded), otherwise apply the pause/resume policy (stay paused; user presses
  Play). On dispose `removeObserver` + `release()`. Tests: recomposition does not re-initialize;
  ready-while-backgrounded → resume → controls enabled and video loads. Audio-focus behaviour of the WebView is **not** assumed — the spike
  tests interruption/focus-loss and we add handling only for what the library demonstrably lacks.
- `rel(0)` only limits related videos to the same channel; acceptable.
- **Captions**: the wrapper exposes no supported on/off toggle; captions follow the viewer's YouTube
  preference. No Captions button (**Δ prototype**).
- BACK → `popBackStack()` to the **origin** (Detail or Home), which restores its focus.

Phase-1 spike: play / pause / seek / BACK / error path on the TV emulator and one real device.

### 7.11 Modals
- **Rate**: slider 0.5–10 step 0.5; default = existing rating or 7.0; LEFT/RIGHT adjust; OK on the slider
  submits; buttons Submit / Remove (if rated) / Cancel; BACK cancels without writing. Word label ≤3 Poor,
  ≤5 Meh, ≤7 Good, ≤8.5 Great, else Masterpiece. After submit: refresh `account_states`; if the title left
  the watchlist (TMDB account preference, D6) the toast says so.
- **Exit**: Stay / Exit app → `finish()`.
- **Import library** (§8.3).

### 7.12 Toasts
Bottom area (never over the player), icon + title + subtitle, 2.4 s, queue of 1. "Synced to your TMDB
account" only after the write succeeded; failures say "Couldn't sync — try again". Announced via
accessibility live region.

---

## 8. Library persistence

### 8.1 Model
`LibraryRepository` exposes `watchlist / favourites / rated` for the current identity (guest or
`account_object_id`) and `toggleWatchlist / toggleFavourite / rate / unrate`.
Writes are serialized per `(MediaRef, kind)`; the button is disabled while pending; optimistic update
rolled back on failure; a rollback never overrides a later successful write (generation check).
v1: signed-in writes while offline fail visibly; no queued sync.

### 8.2 Guest
Room `library_item(media_type, media_id, kind, value, added_at, title_json)`.

### 8.3 Sign-in with existing guest data (D4)
Dialog once per destination account: "Import this TV's guest library into {account}?" —
**Keep separate** (default) / **Import**. Import rules:
1. Read **all pages** of the account's six lists first; any failure aborts before any write.
2. Semantics: watchlist/favourites = union; ratings = TMDB's existing rating wins, local rating only
   written where the account has none.
3. Per title, in order: rating → favourite → watchlist. A title's watchlist write runs only after its rating
   step is **confirmed** (success or skipped); failed / timed-out / unknown rating outcomes block that
   title until reconciled.
4. `import_ledger(account_object_id, media_type, media_id, step, state)`; state is written as
   `attempting` **before** each POST and `done` on success **or** when skipped as already satisfied — so a
   crash after the POST is distinguishable from a never-attempted step and lands in the reconciliation
   path. The Retry for unconfirmed items warns that items removed on TMDB may be re-added. Steps are not retried blindly: on resume (and after any
   unknown outcome) the importer first re-reads that title's `account_states` and re-applies rule 2 — if
   TMDB already has a rating / flag, the step is marked done without writing. This covers the crash window
   "POST succeeded → process died before ledger update → user edited on TMDB". Reconciliation is
    **best-effort**: TMDB has no conditional writes, so any remote edit between our `account_states` read
    and the following POST can be overwritten, and after a crash a remotely *removed* flag or rating is
    indistinguishable from a never-imported one. Policy for that ambiguous case: a step whose prior attempt
    has an unknown outcome and whose remote state is now *absent* is **not** resumed automatically; it is
    listed under "Couldn't confirm {n} items" with a focusable Retry. One unit fixture covers the crash
    window, one the ambiguous-removal case.
5. Guest rows are retained. The dialog states the import cannot be undone automatically.

---

## 9. Feedback

| Event | Haptic (if `Vibrator.hasVibrator()`) | Sound |
|---|---|---|
| focus move | `createOneShot(strengthMs)` | `playSoundEffect(NAVIGATION_*)` |
| row edge | waveform `[0, ms, 40, ms]` | low-volume navigation sound |
| OK | `performHapticFeedback(CONFIRM)` | `CLICK` |

`VIBRATE` permission added only because `Vibrator` is used. When custom sounds are on, native view
sound effects are disabled to avoid doubling. Settings description must say most TV remotes have no
vibrator.

---

## 10. Build phases

| # | Phase | Deliverable | Gate |
|---|---|---|---|
| 1 | Foundation + spikes | secrets→BuildConfig, deps, manifest (§2), credential store, logging policy, `TmdbClient` with token kinds, nav shell + rail + empty screens, loading/error/retry scaffolding, CI (assemble + lint + unit). **Spikes**: (a) auth v4 round-trip on device, (b) YouTube play/pause/seek/BACK/error, (c) focus restore across push/pop and process death | all three spikes green on a real TV |
| 2 | Home | rows, hero (title + person), backdrop, Play-from-Home video fetch | TV checklist pass |
| 3 | Detail · Person · Season | detail DTOs, certification, cast, videos, reviews, facts, episode panel | verify `account_states` append |
| 4 | Auth | Login, QR, polling, sign-out, Settings rows | spike (a) promoted |
| 5 | Library | Room, account lists (two groups), writes, Rate dialog, import dialog + ledger | real account smoke test: watchlist/favourite/rate/unrate |
| 6 | Browse + Search | grids, tabs, pagination, keyboard, trending suggestions | |
| 7 | Settings + Feedback | prefs, language/region reload, haptics/sounds/motion/glow | |
| 8 | Player | §7.10 layout, errors, lifecycle | spike (b) promoted |
| 9 | Release | Exit dialog, About/attribution, R8 release APK on low-memory TV, offline startup, accessibility pass | Android TV quality checklist |

Real-TV test and the TV quality checklist run at the end of every phase.

## 11. Testing

Unit: mappers with real-shaped fixtures (`account_rating`, nulls, blank dates, mixed media ids, partial
pages), token-kind selection, auth state machine (pending / denied / expired / convert-retry / cancel),
error classification, cache keys + invalidation, write serialization + rollback races, language
fallback (D7), certification picker, credit dedupe.
Instrumented: full BACK precedence, modal return-focus, rail show/hide per screen, Search keyboard +
hardware keys, process recreation with focus restore, Player return focus, Reduce-motion snapshots.
Device: release/R8 build, low-memory TV, remote media keys, TalkBack readability, offline cold start.

## 12. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | In-app `android-youtube-player` 13.0.0, controls in a separate strip below the player (no overlay) | ✅ |
| D2 | Keep "Include adult content" (search only) | ✅ |
| D3 | Rail avatar: initials only | ✅ |
| D4 | Guest library on sign-in: ask once; default Keep separate; resumable import, TMDB wins, ratings before watchlist | 🟡 proposed — governs implementation until owner objects |
| D5 | Person hero on Home: department + known-for titles, no extra fetch | 🟡 proposed |
| D6 | Rating/watchlist: follow the account's TMDB preference; never auto re-add; report removal only when observed | 🟡 proposed |
| D7 | Non-`en-US` language: when `overview` / `tagline` / `biography` is blank, one cached `en-US` detail fetch fills only the blank fields, labelled "(English)"; empty English result cached too; skipped when language is already en-US; never for grid cards | 🟡 proposed |
| D8 | `minSdk`: 36 is **emulator/prototype-only**. Before any real-device gate or release, lower to a value from a device/dependency audit (expected 26 or 28); `compileSdk`/`targetSdk` stay | 🔴 owner choice required before Phase 1 real-TV gate |

## 13. API ↔ UX verification notes (kept from v1 review; all rules above already reflect them)

- Trending / search / lists carry `genre_ids` only → genre names from cached genre lists.
- `vote_average` may be documented as integer but returns decimals → `Double`.
- v4 account lists: Bearer = user token, path id = `account_object_id` (string from `/4/auth/access_token`).
- v3 writes: `session_id` query + integer `account_id` from `/3/account`.
- Rating value range/step (0.5–10 × 0.5) and `account_states` inside `append_to_response` are confirmed
  by one live call in Phase 3/5, not by docs.
- Request tokens expire after 15 min; `append_to_response` max 20; rate limit ≈ 40 req/s, honour 429.
- Image sizes used are all present in `/3/configuration`.

## 14. TMDB / platform references
- https://developer.themoviedb.org/docs/getting-started
- https://developer.themoviedb.org/v4/docs/authentication-user
- https://developer.themoviedb.org/reference/authentication-create-session-from-v4-token
- https://developer.themoviedb.org/reference/movie-details · tv-series-details · person-details
- https://developer.themoviedb.org/v4/reference/account-movie-watchlist (and tv / favorites / rated)
- https://developer.themoviedb.org/reference/configuration-details · docs/rate-limiting
- https://www.themoviedb.org/documentation/api/terms-of-use (attribution)
- https://developers.google.com/youtube/terms/required-minimum-functionality (no overlays)
- https://github.com/PierfrancescoSoffritti/android-youtube-player/releases/tag/13.0.0
