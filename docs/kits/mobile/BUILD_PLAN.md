# Mobile (KMP) build plan

Run each milestone on an Android emulator **and** the iOS simulator before moving on.

## M0 — Project (½ day)
- KMP wizard → Android + iOS, shared UI. Package `app.cineverse`.
- Add Koin, Ktor (+ OkHttp / Darwin engines), kotlinx.serialization, Coil 3 (+ ktor network), navigation-compose (multiplatform), lifecycle-viewmodel, DataStore or multiplatform-settings, Room KMP or SQLDelight, BuildKonfig for the TMDB token.
- Fonts in `composeResources/font`: Bricolage Grotesque, Inter.
- ✅ "Hello" screen in both fonts on Android and iOS.

## M1 — Design system (2 days)
Theme + tokens, `PosterCard`, `LandscapeCard`, `PersonCircle`, `Top10Card`, `PillButton`, `ActionTile`, `Chip`, `SegmentedControl` (sliding pill), `ScoreRing`, `FactTile`, `ReviewCard`, `EpisodeCard`, `SettingRow` (toggle/value), `GlassTopBar`, `FloatingTabBar`, `AmbientGlow`, `BottomSheet`, `Snackbar`, `Haptics` (expect/actual), press-scale modifier.
- ✅ A gallery screen shows all components; haptics fire on a real Android phone and iPhone.

## M2 — Data (2 days)
Shared `TmdbApi` (Ktor), DTOs, mappers, repositories, image URL builder from `/configuration`, Fake repository from `sample-tmdb.json`, settings store (language/region/adult/haptics/glow/motion).
- ✅ `commonTest` covers mapping and score colours.

## M3 — App shell (1 day)
Splash, 5 tabs with separate back stacks, top bar behaviours, toasts, edge-to-edge insets, predictive back.

## M4 — Home (2 days)
Hero pager with dots + glow, segmented Trending, rows incl. Top 10, See all links.

## M5 — Detail, Season, Person (3 days)
Sticky tabs, action tiles, rate sheet (stars + slider), cast/videos/reviews/seasons tabs, season chips + episodes, person bio + credits.

## M6 — Explore & Search (2 days)
Paging grids, type segment + list chips, search with debounce, filters with counts, recent searches stored locally.

## M7 — Account & Library (2 days)
Sign-in sheet → Custom Tabs / ASWebAuthenticationSession → deep link → session; secure storage; sync watchlist/favourite/rated; guest library merge.
- ✅ A change on the phone shows up on themoviedb.org.

## M8 — Profile & Settings (1 day)
All toggles persisted and applied (glow, motion, haptics strength, language/region in every request).

## M9 — Player (1 day)
YouTube open-out by default; optional in-app 16:9 player screen as in `12-player`.

## M10 — Polish & release (2 days)
Shimmers, pull-to-refresh, errors/offline, Dynamic Type check, accessibility labels on icon buttons, dark status-bar icons off, app icons, Play/App Store screenshots, privacy labels (TMDB account, no tracking).
