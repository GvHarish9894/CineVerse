# Shell changes (Lane 1 owner + Lanes 3 and 4, merged on `integration`)

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
| (Lane 4) `val onPlayer = destination?.hasRoute<Route.Player>() == true` is computed next to `railScreen`, before the root `Box`. | One Player flag feeds the toast gate, `LibraryActionsHost` and `ImportLibraryHost`. |
| (Lane 4) `NavHost` is wrapped in `LibraryActionsHost(container.libraryRepository, container.appScope, playerActive = onPlayer)` inside the content `Box`. | Provides `LocalLibraryActions` (Home hero Watchlist etc.); the Rate dialog waits while the Player is up (§7.12). |
| (Lane 4) Route `Library` → `LibraryScreen(container, onOpenTitle = Detail, onExplore = reset to Browse(Movies), onSignIn = Login)`. | §7.8. |
| (Lane 4) `ImportLibraryHost(container.libraryRepository, container.libraryImporter, playerActive = onPlayer)` in the root `Box`, after `ToastHost` (under `LocalToaster`). | §8.3 guest → account import prompt; waits while the Player is up. |

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

No change to `FocusMemory`, `rememberFocus`, `restoreRowFocus`, `entryIndex` results, or saved keys.

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

Pending from Lane 4's notes: Lane 2's directional first entry in `FocusMemory.kt` lands with the Lane 2 merge;
the file must keep all three deltas (Lane 1 initial snapshot + public `focusIn`, Lane 2 directional entry,
Lane 4 `lazyIndices`).

Follow-up (D4-b, decided: not in this merge): replace Lane 4's `ui/library/LibraryPosterCard.kt` and the
private `TabChip` in `LibraryScreen.kt` with Lane 1's shared `PosterCard` / `Chip`. They must honour reduce
motion and ambient glow (§6 focus rules), and the Library grid must keep its PinToStart scrolling. Owner: Lane 4.

Noted, not changed: Library content keeps Lane 4's `padding(start = 48)` (sized for master's `Row` rail);
inside Lane 1's 88 inset it starts at x 136, the same as Settings.
