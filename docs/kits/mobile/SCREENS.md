# Mobile screens

Prototype frame 390×844 pt. Screenshots in `screenshots/` (rendered at 2×).

## Global chrome
- **Top bar** (100pt incl. status inset): transparent with a top scrim; becomes frosted glass (`#0C0A18` 80% + 22 blur) after scrolling past the hero/header. Home: logo + wordmark, search button, avatar. Pushed screens: round glass Back button, centred title that fades in when solid; detail adds Share + Favourite (filled accent when on).
- **Tab bar**: floating, 12pt side inset, 16pt from bottom, 70pt tall, radius 26, glass. Tabs: Home, Explore, Search, Library, Profile. Active tab: icon in a 48×30 accent-tinted pill with soft accent glow, white label. A gradient scrim sits behind it. Tapping the active tab pops to its root, or scrolls to top if already there.
- **Ambient glow** behind content, coloured from the current hero/title; neutral accent/violet on tab roots.
- **Toast/snackbar** above the tab bar (2.6s). **Haptic indicator** pill at the top is a prototype debug aid — ship it behind a developer setting only.

## 1. Splash (`01-splash`)
Logo mark 92pt with breathing glow, wordmark 44pt, "Powered by TMDB". 1.6s or until data is ready. On Android use the SplashScreen API for the first frame.

## 2. Home (`02-home`, `03-home-rows`)
- **Hero carousel** (650pt): full-width paging (snap) of 5 featured titles. Each slide: backdrop art + rings + grain, poster 196×294 centred at y112 with coloured shadow, genre chips, title 32pt, meta "2026 · 2h 8m · ★ 8.1", buttons [Trailer (accent)][+ Watchlist / ✓ Saved][ⓘ]. Page dots (active = 22pt accent pill). Each page change = tick haptic.
- Rows (horizontal, snap, 20pt gutters): Trending (+ segmented Today | This week), Popular movies (See all → Explore), **Top 10 movies** (big outlined numbers 120pt overlapping the poster), Popular on TV (landscape 250×141, See all), Now in theatres, Coming soon (gold date badge), Popular people (92pt circles, See all), Top rated TV. Footer attribution.

## 3. Title detail (`04-detail`, `05-detail-overview`, `06-detail-cast`, `10-tv-seasons`)
- Header 380pt backdrop + scrims; poster 116×174 overlapping by 150pt; title 27pt; meta (year · cert · runtime / seasons, then genres); score ring 44pt + "User score" + director/creator.
- Tagline (italic), overview (3 lines + Read more), three action tiles (66pt): Watchlist (accent when on), Rate (gold when rated, shows value), Trailer.
- **Sticky segmented tabs** under the top bar: [Seasons (TV)] Overview, Cast, Videos, Reviews. Pill slides with spring.
  - Overview: 2-col fact tiles, Top billed cast row, More like this row.
  - Cast: list rows (52pt avatar, name, character, chevron).
  - Videos: 350×197 cards with play button → player.
  - Reviews: cards with avatar, author, date, gold score; empty state for unreleased titles.
  - Seasons: cards (70×105 art, Season n, episodes · year, one-liner) → Season screen.

## 4. Season (`11-episodes`)
Header (show · network, "Season n" 34pt), season chips (white = selected), episode cards: still 132×74 with play glyph, "S1 · E3", name, "date · runtime · ★", 2-line overview → player.

## 5. Person (`08-person`)
Centred portrait circle 150 with coloured glow, name 30pt, chips (Actor/Director, Born …, place), biography 4 lines + Read more, Known for row, credits list (year, mini poster, title, role).

## 6. Explore (`09-explore`)
Large title, subtitle per type, segmented Movies | TV shows | People, list chips (Popular, Now playing, Upcoming, Top rated / Popular, Airing today, On TV, Top rated), 3-column grid (posters 2:3, or 96pt person circles). Infinite scroll in the real app.

## 7. Search (`13-search`)
Large title, search field (focus = accent ring, clear button). Empty: Recent chips + numbered "Trending searches" list. Typing: filter chips with counts (All, Movies, TV, People) + result rows (poster 58×87 or round avatar, title, "Movie · 2026 · ★ 8.1"). No results: illustration + hint. Debounce 300ms; keyboard "Search" action dismisses keyboard.

## 8. Library (`14-library`)
Large title, sync status line, segmented Watchlist | Favourites | Rated (with counts), sign-in banner when guest, 3-col grid (rated items get gold "★ 8.0" badges), empty state with rings illustration + [Explore movies].

## 9. Profile (`15-profile`)
Gradient profile card: avatar, name / Guest, sync status, stats (Watchlist, Favourites, Ratings), [Sign in with TMDB] when guest. Groups:
- Feel: Haptic feedback (toggle), Haptic strength (tap cycles Light/Medium/Strong), Ambient glow, Reduce motion, Autoplay trailers, Haptic indicator (debug).
- Content: Content language, Region (tap cycles — use a picker sheet in the real app), Include adult titles.
- Sign out (when signed in), attribution text.

## 10. Sheets
- **Sign in** (`16-sign-in-sheet`): handle, kicker, title, 3 steps, [Not now][Continue with TMDB] → spinner "Waiting for TMDB…" → success haptic + toast.
- **Rate** (`07-rate-sheet`): 5 stars (tap = whole star, tap again = half), value 44pt, word, range slider 0.5–10 step 0.5 (tick haptic per step), [Remove][Submit rating]. Drag-to-dismiss on the handle; tap scrim closes.

## 11. Player (`12-player`)
Full-screen black, chevron-down close, 16:9 frame with play/pause overlay and accent progress, title, controls [−10][Play/Pause][+10][CC], "More videos" list. Rotating to landscape goes full-bleed.

## States to add
Shimmer placeholders in the same sizes, pull-to-refresh on Home/Explore/Library, inline error + retry, offline banner.
