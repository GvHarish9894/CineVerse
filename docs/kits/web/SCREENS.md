# Web pages

Designed at 1440px wide; content max 1320. Screenshots in `screenshots/` (`02-home-full` is the whole page).

## Global
- **Header** (72px, sticky, glass `#090812` 74% + blur, hairline bottom border): logo (36px mark + wordmark) · Movies ▾ (Popular, Now playing, Upcoming, Top rated) · TV Shows ▾ (Popular, Airing today, On TV, Top rated) · People · My Library · search field (340px, accent ring on focus; typing jumps to the Search page) · [Sign in] or avatar menu (Watchlist, Favourites, Ratings, Settings, Sign out). Menus open on click, close on pointer leave / Esc.
- **Footer**: logo + one-liner, Browse links, You links, TMDB attribution.
- **Ambient glow** at the top of every page (1100px tall), from the hero/detail title's colours.
- **Toast** bottom-left, 2.8s.
- URL per page (see CLAUDE.md).

## 1. Home (`01-home`, `02-home-full`, `03-card-hover`)
- **Hero** 660px: backdrop with rings + grain, left + bottom scrims. Eyebrow ("Movie · #1 trending this week"), title 76px, score ring 52px + meta, overview (max 580px), [Play trailer (accent)][Add to watchlist][More info].
- **Picks strip** bottom-right: 5 thumbnails 156×88 with labels; active one has a white ring and an 8s progress bar; the hero auto-advances every 8s and **pauses while hovered**. Clicking a pick switches immediately.
- Sections (each: title 26px, optional segmented control, ←/→ scroll buttons; rows bleed to the window edge but start aligned with the content column):
  1. Trending — Today | This week
  2. What's popular — Movies | On TV
  3. **Latest trailers** — 340×191 video cards; the section background becomes the hovered trailer's backdrop (with scrims)
  4. Sign-in CTA banner (guests only)
  5. Now playing in theatres
  6. Coming soon (gold date badges)
  7. Popular people (156px portrait cards)
  8. Top rated
- **Poster card** 180×270, radius 16; score ring 40px overlapping the bottom-left edge; title 15px bold + date. Hover: lifts 6px, scales 1.03, coloured glow + white ring, quick-action button (top-right, + / ✓ watchlist) appears. Keyboard focus shows the same.

## 2. Movies / TV Shows (`04-movies-menu`, `05-movies`)
Page title 48px + description, list chips, then a two-column layout: **filters aside** (Sort select: Popularity / Rating / Release date / Title; Genre chips multi-select with ✓; Clear genres) and an auto-fill grid (min 170px). Count line ("6 titles · filtered by Thriller"). Empty state when filters remove everything. Real app: `/discover` with paging ("Load more" or infinite scroll).

## 3. People
Popular people grid (min 150px) — portrait card + known-for line.

## 4. Title detail (`06-detail`)
- **Hero band**: full-width backdrop + strong left scrim; "← Back to …" link; poster 300px (2:3) with coloured shadow; title 52px + (year); facts line (cert badge outline · date (region) · genres · runtime/seasons); score ring 72px + "User score" + round action buttons 50px with hover tooltips (Watchlist, Favourite, Rate) + Play trailer; tagline; Overview; crew grid (Director, Screenplay, Producer).
- **Body**: main column (Top billed cast cards 150×180 portrait, Current season card for TV → season page, Reviews, Videos row) + right aside (Status, Language, Runtime, Certification, Release date / Network, Type, Episodes, Keywords chips). Aside stacks below on narrow screens.
- Recommendations row.

## 5. Season (`10-season`)
Back link, small poster + "Show · Network" + "Season n" 48px, season chips, episode cards (still 240×135, number, name, ★ badge, date · runtime, overview). Click plays.

## 6. Person (`08-person`)
Left: portrait 300 (2:3), Personal info panel (Known for, Credits, Birthday, Place of birth). Right: name 48px, Biography (4 lines + Read more), Known for row with score rings, credits table (year · colour dot · title · role).

## 7. Search (`09-search`)
Big search field (60px), facets aside with counts (All, Movies, TV shows, People), result rows (86px poster or round avatar, title, type · date · ★, two-line overview). Empty query shows trending searches.

## 8. My Library (`13-library`)
Tabs with counts (Watchlist · n, Favourites · n, Ratings · n), poster grid (gold rating badges on Ratings), empty state with [Browse popular movies].

## 9. Settings
Three panels in an auto-fill grid: Look and feel (Ambient glow, Reduce motion, Autoplay trailers, Haptic taps on phones), Content (Content language select, Region select — each shows its API param, Include adult titles), Account (sign in/out).

## 10. Dialogs
- **Sign in** (`12-sign-in`): 560px dialog, 3 steps, [Continue to TMDB] opens the approve URL in a new tab; when the user returns the proxy completes the session ([Not now] closes).
- **Rate** (`07-rate`): 5 large stars with **half-star hover preview**, value "7.5 / 10", word, [Submit][Remove].
- **Trailer** (`11-trailer`): 1000px 16:9 player with hover-revealed centre button, control bar (play/pause, state, progress, duration, captions), "More videos" strip. Real app: embed the YouTube iframe player in an HTML overlay above the canvas (Compose Web interop) — canvas can't host it directly.
- All dialogs: scrim 72% + 12px blur, focus trapped, Esc closes, focus returns to the trigger.

## Breakpoints (`14-home-phone-width`)
- ≤1000px: picks strip hidden, hero title 56px.
- ≤860px: nav links collapse into a menu button with a drop-down list; search field stretches; wordmark hidden.
- ≤640px: 16px gutters, hero 560px / title 42px, posters 140px, detail poster 200px, aside full width, dialogs tighter padding.

## States to add
Skeleton cards, error + retry per section, 404 page for unknown ids, offline toast.
