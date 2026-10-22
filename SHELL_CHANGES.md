# Shell changes (Lane 1 owner + Lanes 2, 3 and 4, merged on `integration`)

Lane 1 owns the §5 shell for wave 2 (rail expand-on-focus, clock, ambient backdrop) besides Home / Browse /
Search. These are deliberate Lane 1 deliverables, not WIP. Unchanged vs master: BACK precedence
(`BackHandler` / `backAction`), rail visibility (`isRailScreen`: no rail on Login, Detail, Person,
Season, Player), `FocusMemory` storage format, `HomeBackHandle`. Lane 3's shell edits (below) change
neither.

## `ui/nav/AppNav.kt`
| Change | Why |
|---|---|
| Layout: master's `Row(rail, content)` → `Box` with the content inset by `RAIL_COLLAPSED_WIDTH` (88 design units) on rail screens, and the rail drawn **over** the content. A scrim covers the content while the rail has focus. | §5 / prototype `.nav-scrim`: the expanded rail (268) overlays the content instead of re-flowing it. Home / Browse / Search insets assume this (prototype x 120 = 88 + 32). |
| `Clock` (new `ui/nav/Clock.kt`, `HH:mm`, 15 s) at top-right (`top 30, end 48`), on every screen but Splash and Player. | §5 clock; nothing is drawn over the player. |
| `NavRail(accountName = credentials?.displayName)`, collected from `authRepository.credentials`. | §5 rail account (D3). |
| `Toaster` created here, provided as `LocalToaster`; `ToastHost(toaster, Modifier.align(BottomCenter).padding(bottom = 44.dp))` is the last child of the root `Box`, **not composed on Player** (a toast waits in the queue until the player closes). | Pinned seam (§6, §7.12): bottom area, never over the player. |
| Routes: `Home` → `HomeScreen(container, onOpen, backHandle)`; `Browse` → `BrowseScreen(container, kind, onOpen)`; `Search` → `SearchScreen(container, onOpen)`. | Lane 1 screens (Home needs `container` now). |
| (Lane 3) Routes: `Settings` → `SettingsScreen(onSignIn = { nav.navigate(Route.Login) })`; `Login` → `LoginScreen(onContinueAsGuest = leaveLogin, onSignedIn = leaveLogin, message = loginMessage)`. Both show their own toasts via `LocalToaster`. | §7.9, §4.1. |
| (Lane 3) `.navigationFeedback(LocalFeedback.current, LocalFocusManager.current)` on the root `Box` (holds rail + content), once. | §9 D-pad hook. Adding it anywhere else in the main window would make OK give feedback twice. |
| (Lane 4) `val onPlayer = …` is computed next to `railScreen`, before the root `Box`. (Final review #1) It is true while the current destination **or any of `nav.visibleEntries`** is a Player, so it also covers an outgoing Player still shown by the NavHost transition. | One Player flag feeds the clock, the toast gate, the Exit dialog, `LibraryActionsHost` and `ImportLibraryHost`. |
| (Lane 4) `NavHost` is wrapped in `LibraryActionsHost(container.libraryRepository, container.appScope, playerActive = onPlayer)` inside the content `Box`. | Provides `LocalLibraryActions` (Home hero Watchlist etc.); the Rate dialog waits while the Player is up (§7.12). |
| (Lane 4) Route `Library` → `LibraryScreen(container, onOpenTitle = Detail, onExplore = reset to Browse(Movies), onSignIn = Login)`. | §7.8. |
| (Lane 4) `ImportLibraryHost(container.libraryRepository, container.libraryImporter, playerActive = onPlayer)` in the root `Box`, after `ToastHost` (under `LocalToaster`). | §8.3 guest → account import prompt; waits while the Player is up. |
| (Lane 2) Routes `Detail` / `Person` / `Season` → `DetailScreen` / `PersonScreen` / `SeasonScreen(it.toRoute(), container, onNavigate = { route -> nav.navigate(route) })`. `isRailScreen` unchanged: no rail on them. | §7.4–§7.6. |
| (Integration) The content `Box` is `.focusRequester(contentFocus).focusRestorer().focusGroup()`: the restorer owns **one** explicit focus group, the screen. | See "Focus root" below. |

Merge notes for the remaining lanes: Lane 2 replaces the Detail / Person / Season lines inside
`LibraryActionsHost { NavHost { … } }`. If a lane rewrites the AppNav scaffold, keep all three `onPlayer` gates
(toast, `LibraryActionsHost`, `ImportLibraryHost`) and **one** `Toaster` / `ToastHost` (this placement).

Toast policy: non-essential toasts are held while the Player route is active — one waiting message is kept
(a newer one replaces it); a toast already showing when Player opens is disposed, not replayed; Player errors
are inline, not toasts. Lane 4's Rate / Import hosts get the same route flag (`playerActive = onPlayer`) and
wait while the Player is up.

## `ui/nav/NavRail.kt`
Public surface: `NavRail(current: RailItem?, onSelect: (RailItem) -> Unit, modifier: Modifier = Modifier,
accountName: String? = null)` (`modifier` before `accountName`) and `val RAIL_COLLAPSED_WIDTH = 88.dp`.
`RailItem` unchanged.

| Change | Why |
|---|---|
| Width 88 → 268 design units while focused (300 ms, snap with Reduce motion), labels only when expanded; transparent when collapsed. | §5 expand-on-focus, prototype sizes. |
| `focusProperties { onEnter }`: focus entering the rail lands on `current`. | §1.2: LEFT into the rail opens it on the current item, not the nearest one. |
| Items drawn with `FocusSurface` (white focus fill, accent marker for current, `contentDescription = label`, `selected`). | Prototype rail style; same focus visuals as the rest of Lane 1. |
| Logo row on top; account row at the bottom: initials of `accountName` (via `initials()` in `PersonCard.kt`), or "G" + "Guest". | §5 / D3: initials only. |

Lane 3's own rail account footer (`RailAccount` / `accountInitials`) was dropped at merge; its
`AccountInitialsTest` now tests `ui/components/initials`, which this rail uses.

## `ui/components/FocusMemory.kt` (`FocusRestorer`)
| Change | Why |
|---|---|
| `savedAtEntry`: the screen's `SavedFocus` (row, item key, index) captured when the restorer is created; the **first** `restore()` targets it, then clears it so later restores read the memory again. | After a pop the window regains focus and default focus lands on (e.g.) item 0 of the saved row, overwriting the memory before `restore()` runs; the screen then restored to the wrong item (review finding 5). |
| `focusIn(row, index)` is public (was private). | Search moves focus to the first result after Clear (§7.7). |
| (Lane 4) `FocusRow.lazyIndices` (optional, default null): the lazy-item index of each item key; `focusIn` scrolls by it. | The Library grid has header items, so item index ≠ lazy index; it restores by lazy index. |
| (Lane 2, folded with the fix below) `FocusRestorer.entryKey(row, direction)`: a D-pad entry into a row lands on its remembered item, else its **first** item (prototype `rowMem`), not the geometrically nearest one; `Enter` (programmatic) → null, the request keeps its target. | Prototype row entry; changes Lane 1's rows (Home / Browse / Search), Settings and Library too. Covered by `FocusMemoryTest` and `FocusRestorerRowEntryTest`. |
| (Integration fix after the Lane 4 merge) `restoreRowFocus`'s `onEnter` redirects only D-pad entries to the row's remembered item; a programmatic `requestFocus` (`requestedFocusDirection == FocusDirection.Enter`) keeps its own target. A screen that wants row memory on a programmatic entry calls `restorer.restore(…)`. | §7.7: Search's after-Clear `focusIn(first result)` was redirected to the remembered trending item (and `SearchDpadTest.everyClearMovesFocusToTheFirstResult` failed because its `requestFocus` on the `x` key was redirected to the remembered `Clear` key). Covered by `SearchDpadTest.clearFocusesTheFirstResultEvenWhenAnotherTrendingResultIsRemembered`. |

No change to `FocusMemory`, `rememberFocus`, `restoreRowFocus`, `entryIndex` results, or saved keys.

## Focus root (AppNav `focusRestorer`)
`Modifier.focusRestorer()` has no focus target of its own, so on AppNav's content `Box` its `onEnter` redirect
applied to every top-level focus group inside the screen: a programmatic `requestFocus()` into an inner group was
sent to the last focused item elsewhere (device: opening the Season episode panel sent focus from Close back to
the last focused season). Lane 2 worked around it with `Modifier.screenFocusRoot()` (`fillMaxSize().focusGroup()`)
on its three screens and left the integrator a choice (FOCUS_NOTES.md): put that root on every screen under AppNav,
or let AppNav's restorer own an explicit group.

**Chosen: AppNav's content is `.focusRestorer().focusGroup()`.** One line covers every screen under AppNav
(including the spikes and future screens) instead of nine call sites that each must remember it. Rail ↔
content and `contentFocus.requestFocus()` now enter that group, so the restorer still restores the last focused
item on re-entry; focus moves inside the screen are left alone. `ScreenFocusRoot.kt` is deleted and Detail /
Person / Season roots are plain `fillMaxSize()`. `SeasonScreenDpadTest` wraps the screen in
`focusRestorer().focusGroup()` as AppNav does; with a bare `focusRestorer()` wrapper and no screen root it
fails (checked on emulator-5554), so it guards the regression. All 34 `ui.*` instrumented tests pass.

## Also touched outside `ui/nav`
- `MainActivity.kt`: one `settings` `State` (`collectAsStateWithLifecycle(Settings())`, kept as `State`, not
  `by`) feeds both `LocalSettings provides settings.value` (Lane 1) and `ProvideFeedback(settings)` (Lane 3,
  `AndroidFeedback` reads it on every key press). Nesting:
  `LocalSettings { DesignCanvas { CineVerseTheme { ProvideFeedback { Surface { AppNav } } } } }`.
  `ProvideFeedback` is installed once; it also turns off the view's own sound effects while in-app sounds
  are on (§9).
- `ui/components/ExitDialog.kt`: dialog content wrapped as `DesignCanvas { FeedbackRoot { … } }` — a Dialog
  is a new composition root (§6 units) and its own window, so AppNav's §9 hook never sees its keys. Lane 4's
  `RateDialog` / `ImportLibraryDialog` do the same.
- `ui/components/Toasts.kt`: the pinned `class Toaster { fun show(title: String, subtitle: String? = null) }`,
  `val LocalToaster`, `ToastHost(toaster, modifier)`: 2.4 s, queue of 1 (newest waiting replaces older),
  polite live region. `ToastHost` here is only as large as the toast and the caller places it (Lane 3's /
  Lane 4's copy fills the screen) — this file is the one kept. Lane 4's `ToastHost(…, paused)` was not merged:
  the AppNav route gate gives the same behaviour.
- Domain merge note: canonical files are `domain/MediaRef.kt`, `Title.kt`, `Person.kt`, `LibraryState.kt`,
  `LibraryActions.kt` — byte-identical across lanes (`MediaRef` and its `key` live only in `MediaRef.kt`).

## Lane 3 (Login / Settings / Feedback / About): what it changed in shared files
Shell files (`MainActivity`, `AppNav`, `NavRail`, `ExitDialog`, `Toasts`): see the sections above; nothing
else of Lane 3's remains in them.

Other shared (non-shell) changes:
- `ui/feedback/Feedback.kt`: real §9 feedback — `ProvideFeedback(settings: State<Settings>)`, `LocalFeedback`,
  `Modifier.navigationFeedback(feedback, focusManager)` and `FeedbackRoot { }` for Dialog/Popup content.
- `data/auth/AuthRepository.kt`:
  - `SignInOutcome.SignedIn` now also carries `account: AccountDto`.
  - `signOut(onSignedOutLocally: () -> Unit = {})` calls back after the local clear and before remote
    revocation. The default keeps existing callers unchanged.
- `data/settings/SettingsRepository.kt`: account-defaults offers are stored per `account_object_id`:
  - `pendingAccountDefaults` holds the open offers;
  - `offerAccountDefaults(defaults)` adds one;
  - `resolveAccountDefaults(accountObjectId, apply)` closes one.

## Merge log
### lane3-wave2 → integration
| File | Conflict | Resolution |
|---|---|---|
| `SHELL_CHANGES.md` | add/add | This document: Lane 1's as base, Lane 3's shared-file changes appended, NavRail order fixed (`modifier` before `accountName`), applied keep/drop instructions removed. |
| `MainActivity.kt` | content | Lane 1's `LocalSettings` + `DesignCanvas` + theme, with Lane 3's `ProvideFeedback` around `Surface`; one `settings` `State` for both. |
| `ui/components/ExitDialog.kt` | content | `DesignCanvas { FeedbackRoot { … } }`. |
| `ui/components/Toasts.kt` | add/add | Lane 1's file (caller-placed host, Player gating in AppNav, live region, queue of 1). Lane 3's call sites (`LoginScreen`, `SettingsScreen`) only use `LocalToaster.current.show(…)`, same shape. |
| `ui/nav/AppNav.kt` | content | Lane 1's file; added Lane 3's Settings / Login route bodies and `navigationFeedback` on the root `Box`. Dropped Lane 3's duplicate `credentials` collection, its `Toaster`/`ToastHost` and its `Row` layout. |
| `ui/nav/NavRail.kt` | content | Lane 1's file; Lane 3's `RailAccount` / `accountInitials` dropped. |
| `test/.../ui/nav/AccountInitialsTest.kt` | none (follow-on) | Points at `ui/components/initials` (Lane 3's `accountInitials` no longer exists). |

`docs/IMPLEMENTATION_SPEC.md` and the `domain/` files merged without change (identical on both sides).

### lane4-wave2 (2a94515) → integration
| File | Conflict | Resolution |
|---|---|---|
| `domain/MediaRef.kt`, `domain/Title.kt` | add/add | Integration's copies (Lane 4's differ only in formatting / doc comment). |
| `ui/components/DesignCanvas.kt` | add/add | Integration's (Lane 1's real canvas); Lane 4's pass-through stand-in dropped. |
| `ui/components/Toasts.kt` | add/add | Integration's file. Lane 4's `paused` parameter not merged: AppNav's `if (!onPlayer) ToastHost(…)` gives the same behaviour (newest toast waits in the 1-slot queue, the showing one is dropped); no Lane 4 code or test uses `paused`. |
| `ui/components/FocusMemory.kt` | content | Union: Lane 1's public `focusIn` with Lane 4's `lazyIndex` body; `FocusRow.lazyIndices` auto-merged. |
| `ui/nav/AppNav.kt` | content | Integration's file plus the four Lane 4 rows in the AppNav table above; Lane 4's own `Toaster` / `ToastHost` and `Row` layout dropped. |
| `ui/components/RateDialog.kt`, `ImportLibraryDialog.kt` | none (follow-on) | `Dialog { DesignCanvas { FeedbackRoot { … } } }`, as `ExitDialog`. |
| `MERGE_NOTES.md` | none (new file) | Still-valid points moved here (FocusMemory deltas, dialogs, AppNav `onPlayer` gates, cards follow-up); the `paused` Toasts request is superseded (see `Toasts.kt` row). File deleted. |

D4-b (decided: not in this merge) was done after the Lane 2 merge, see below.

Noted, not changed: Library content keeps Lane 4's `padding(start = 48)` (sized for master's `Row` rail);
inside Lane 1's 88 inset it starts at x 136, the same as Settings.

Integration fix after this merge (`c864c85`): `SearchDpadTest.everyClearMovesFocusToTheFirstResult` failed at
`SearchDpadTest.kt:115`. `restoreRowFocus`'s `onEnter` sent every entry into a row, programmatic `requestFocus`
included, to the row's remembered item, so the test's `requestFocus` on the `x` key went to the remembered `Clear`
key (and in the app, after Clear, focus went to a remembered trending item instead of the first result, §7.7).
Only D-pad entries use the row memory now (FocusMemory table above).

### lane2-wave2 (cd8b817) → integration (`18d67ef`)
Dry-run first at 1e4094e, then again at cd8b817 (9 more commits) over integration `8d226d8`; the conflict set did
not change. Since 1e4094e Lane 2 dropped its own `tmdbImageUrl(base, size, path)` (which clashed with
`TmdbImage.kt`'s), and its `TmdbImage.kt` / `LocalSettings.kt` are byte-identical to integration's.

| File | Conflict | Resolution |
|---|---|---|
| `domain/MediaRef.kt`, `domain/Title.kt` | add/add | Integration's copies. |
| `ui/nav/AppNav.kt` | content | Integration's file; the three placeholder routes → the Lane 2 screens (AppNav table above). `PlaceholderScreen` import dropped. |
| `ui/components/FocusMemory.kt` | content (`restoreRowFocus` `onEnter`) | Lane 2's `entryKey` (column 0 on a D-pad entry with no memory) folded into `c864c85`'s rule: `entryKey` returns null for `Enter` whether or not a key is remembered, and the async `saved` fallback only runs for D-pad entries. `FocusMemoryTest`'s Enter-with-memory assertion flipped to `assertNull`. |

Auto-merged: `data/tmdb/TmdbRepository.kt` (`season(…, language)` next to Lane 4's edits), debug `HttpLogging.kt`
(+ redaction test), domain `Credit/Detail/Episode/Review/Season/Video.kt`, `DetailMappers.kt`, the Lane 2 cards,
screens and tests.

Follow-ups, one commit each:
- `8f8ea6c` Season's "Season not available" uses `ui/components/Toasts.kt`'s `LocalToaster` (Lane 2's no-op
  stand-in deleted).
- `6f5d353` Shared cards (D4-b + Lane 2): Detail / Person / Season / Library use Lane 1's `PosterCard`,
  `PersonCard`, `LandscapeCard`, `ScoreRing`, `Chip`, `ActionButton` (all on `FocusSurface`: Reduce motion snaps
  the scale, Ambient glow off drops the glow). Deleted: `Lane2PosterCard`, `Lane2PersonCard`, `Lane2LandscapeCard`,
  `DetailScoreRing`, `DetailActionButton`, Lane 2's `posterSubtitle`, `ui/library/LibraryPosterCard.kt` (+
  `posterSubline`), `TabChip`, and `LibraryUiState.imageBaseUrl` (only the lane card read it). Kept: Lane 2's
  `ReviewCard` / `FactCard` / `CreditCard` / `SeasonCard`, and `DetailChip`, the non-focusable hero metadata chip
  (prototype `.mchip`; the §6 `Chip` is a focusable tab / filter). Additive: `LandscapeCard(imageUrl = …)` for
  Detail's YouTube thumbnails. Detail rows keep `PinToStart` (`DetailRow`). Shared cards put the caller's modifier
  on their column, so `LibraryContentTest` finds a card as the focusable child of its tagged column, and
  `PersonScreenDpadTest` matches the poster description by substring (the shared card adds the sub-line).
- `d86b626` Focus root (section above).
- `af4f8ad` Detail: one `activeReview` drives both the review panel and its `BackHandler` (Lane 2 r2 LOW).

Noted, not changed: Lane 2's `initialsOf` duplicates `initials`, and `DetailCardSurface`'s image box duplicates
`TmdbImage`. Lane 2 reads Reduce motion through `LocalFeedback.current.reduceMotion`, Lane 1 through
`LocalSettings.current.motion`; both come from the same settings `State` in MainActivity. The shared
`posterSubtitle` shows "{year} · Unrated" for a past unrated title (Lane 2 / 4 showed "Unrated"; §6 says
"Unrated"). The Library grid has no `PinToStart` (it never had one; its section headers would scroll away).

`INTEGRATION_PLAN.md` (dry runs and intended resolutions for lanes 4 and 2) is folded into this log and deleted.

## Final review follow-ups
Fixed on `master`, one commit each, each with a regression test:

| # | Finding | Fix | Tests |
|---|---|---|---|
| 1 | [HIGH] A delayed Play-trailer response opened the Player after BACK had opened the Exit dialog: Exit drawn over the playing video. | `81be6b3`: Home drops a pending trailer navigation when its window loses focus (`LocalWindowInfo.isWindowFocused` → `HomeViewModel.setWindowFocused`), not only when it leaves RESUMED. AppNav ties the Exit dialog to the back-stack entry it was opened on (`exitDialogEntry`) and closes it when another entry becomes current. Exit, Rate and Import render only while `!onPlayer` (see the AppNav table: includes an outgoing Player from `nav.visibleEntries`), checked synchronously at the render site, not only through the controllers' `shown` flows. `AppContainer(context, tmdbApi = …)` takes an optional API for tests. | JVM `HomeViewModelTest."pending trailer navigation is dropped when window focus is lost before the fetch completes"`; instrumented `ui.nav.ExitDialogOverPlayerTest` (slow fake `/videos`; both cases fail on the pre-fix code). |
| 2 | [MEDIUM] KEYCODE_MEDIA_PLAY_PAUSE ignored during playback (TV-PP). | `883b84e`: the Player root handles MEDIA_PLAY_PAUSE / PLAY / PAUSE (the Play/Pause button's logic) and MEDIA_REWIND / FAST_FORWARD (−10 s / +10 s) whichever control is focused; one action per key-down. | `PlayerLifecycleTest.mediaKeysToggleAndSeekOncePerPressWhicheverControlIsFocused` (fake `PlayerHost`). |
| 3 | [MEDIUM] A long "Couldn't confirm {n} items" list pushed the re-add warning and Done / Retry off the Import dialog. | `3c3f0b7`: titles in a height-bounded `ScrollableText`; the dialog's initial focus entry is pinned to its default button. | `ImportLibraryDialogTest.hundredUnconfirmedTitlesKeepWarningDoneAndRetryReachable`. |
| 4 | [LOW] Launcher banner without the app name (TV-BN). | `8854a12`: 320×180 vector, play mark + "CineVerse" wordmark (Roboto Bold outlines) on the brand gradient; still `android:banner`. Not localised. | Build / lint only. |

Remaining owner gates (not done here):
- **D8 minSdk**: RESOLVED in KMP L1/P3 — tvApp minSdk is 28 (band 26–31; TV-PS ≤ 31). API-28 runtime acceptance on a real TV still pending.
- Live TMDB sign-in / account smoke (sign-in, library sync, guest import) against a real account.
- Physical Android TV device pass.
- YouTube policy check on device (§7.10 RMF: branding, ads, client identity).
