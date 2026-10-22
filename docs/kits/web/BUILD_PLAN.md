# Web (Compose for Web) build plan

## M0 — Project (½ day)
- KMP wizard → targets Web (Wasm) + Server (Ktor); if the mobile repo exists, add the `wasmJs` target to its `composeApp` and a `server` module instead.
- Fonts as compose resources; theme from `docs/DESIGN_TOKENS.md`.
- `./gradlew :composeApp:wasmJsBrowserDevelopmentRun` serves the app; `./gradlew :server:run` serves the proxy.
- ✅ Hello page in both fonts in Chrome, Firefox and Safari.

## M1 — Proxy server (1 day)
Ktor routes: `GET /api/tmdb/{path...}` forwards to TMDB with the bearer token from an env var, passes `language/region/include_adult`, caches list endpoints 5–10 min; `/api/auth/token`, `/api/auth/session` (stores `session_id` in an http-only, SameSite=Lax cookie), `/api/account/**` adds the session. Rate-limit and CORS locked to your origin.
- ✅ The browser never sees the token (check the Network tab).

## M2 — Design system (2 days)
Header with dropdowns, `PosterCard` (hover lift + quick action), `PersonCard`, `VideoCard`, `ScoreRing`, `SegmentedControl`, `Chip`, `RoundActionButton` with tooltip, `Panel`, `FactList`, `ReviewCard`, `EpisodeCard`, `Dialog` (focus trap, Esc), `Toast`, `AmbientGlow`, `Scrims`, `ScrollRow` with arrows, footer.
- ✅ Gallery page; keyboard focus ring visible on everything.

## M3 — Routing & shell (1 day)
Navigation bound to browser history; routes `/`, `/movies?list=`, `/tv?list=`, `/people`, `/movie/{id}`, `/tv/{id}`, `/tv/{id}/season/{n}`, `/person/{id}`, `/search?q=`, `/library?tab=`, `/settings`. 404 page.
- ✅ Refresh on any URL restores the page; Back/Forward work.

## M4 — Home (2 days)
Hero with auto-advance + pause on hover + picks strip, all sections incl. trailer background swap, CTA for guests.

## M5 — Browse, People (1–2 days)
Discover-backed grids with sort + genre filters reflected in the URL, paging.

## M6 — Detail, Season, Person (2–3 days)
Hero band, actions + tooltips, cast, current season, reviews, videos, aside, recommendations; season page; person page with credits table.

## M7 — Search (1 day)
Header field → `/search?q=` with 300ms debounce; facets with counts; results list.

## M8 — Account & library (2 days)
Sign-in dialog → new tab approve → session cookie; watchlist/favourite/rate; guest library in localStorage with merge on sign-in.

## M9 — Trailer player (1 day)
HTML overlay with the YouTube iframe API over the canvas (Compose Web HTML interop); custom chrome as in `11-trailer` or the YouTube default controls.

## M10 — Responsive & polish (2 days)
Breakpoints from SCREENS.md, skeletons, errors, reduced motion, accessibility labels, Lighthouse pass on the HTML shell, deploy (static Wasm files on a CDN + the Ktor server, or both behind one domain so cookies just work).
