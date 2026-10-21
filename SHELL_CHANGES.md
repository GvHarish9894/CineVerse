# Lane 1 shell changes (for the merge integrator)

Lane 1 owns the §5 shell for wave 2 (rail expand-on-focus, clock, ambient backdrop) besides Home / Browse /
Search. These are deliberate Lane 1 deliverables, not WIP. Unchanged vs master: BACK precedence
(`BackHandler` / `backAction`), rail visibility (`isRailScreen`: no rail on Login, Detail, Person,
Season, Player), `FocusMemory` storage format, `HomeBackHandle`.

## `ui/nav/AppNav.kt`
| Change | Why |
|---|---|
| Layout: master's `Row(rail, content)` → `Box` with the content inset by `RAIL_COLLAPSED_WIDTH` (88 design units) on rail screens, and the rail drawn **over** the content. A scrim covers the content while the rail has focus. | §5 / prototype `.nav-scrim`: the expanded rail (268) overlays the content instead of re-flowing it. Home / Browse / Search insets assume this (prototype x 120 = 88 + 32). |
| `Clock` (new `ui/nav/Clock.kt`, `HH:mm`, 15 s) at top-right (`top 30, end 48`), on every screen but Splash and Player. | §5 clock; nothing is drawn over the player. |
| `NavRail(accountName = credentials?.displayName)`, collected from `authRepository.credentials`. | §5 rail account (D3). |
| `Toaster` created here, provided as `LocalToaster`; `ToastHost(toaster, Modifier.align(BottomCenter).padding(bottom = 44.dp))` is the last child of the root `Box`, **not composed on Player** (a toast waits in the queue until the player closes). | Pinned seam (§6, §7.12): bottom area, never over the player. |
| Routes: `Home` → `HomeScreen(container, onOpen, backHandle)`; `Browse` → `BrowseScreen(container, kind, onOpen)`; `Search` → `SearchScreen(container, onOpen)`. | Lane 1 screens (Home needs `container` now). |

Merge notes: Lane 2 replaces the Detail / Person / Season lines, Lane 3 Settings / Login, Lane 4 Library and
wraps `NavHost` in `LibraryActionsHost` + hosts `ImportLibraryHost` — all inside the content `Box` above.
Lane 3's `navigationFeedback` modifier goes on the root `Box` (it was on master's `Row`). Lane 4 and
Lane 3 each install their own toast host: keep **one** `Toaster` / `ToastHost` (this placement); Lane 3's
`AccountToasts` / `AccountToastHost` should become `LocalToaster.current.show(...)`.

## `ui/nav/NavRail.kt`
Public surface: `NavRail(current: RailItem?, onSelect: (RailItem) -> Unit, modifier: Modifier = Modifier,
accountName: String? = null)` and `val RAIL_COLLAPSED_WIDTH = 88.dp`. `RailItem` unchanged.

| Change | Why |
|---|---|
| Width 88 → 268 design units while focused (300 ms, snap with Reduce motion), labels only when expanded; transparent when collapsed. | §5 expand-on-focus, prototype sizes. |
| `focusProperties { onEnter }`: focus entering the rail lands on `current`. | §1.2: LEFT into the rail opens it on the current item, not the nearest one. |
| Items drawn with `FocusSurface` (white focus fill, accent marker for current, `contentDescription = label`, `selected`). | Prototype rail style; same focus visuals as the rest of Lane 1. |
| Logo row on top; account row at the bottom: initials of `accountName` (via `initials()` in `PersonCard.kt`), or "G" + "Guest". | §5 / D3: initials only. |

Collision: Lane 3 adds its own `accountName` + `RailAccount` / `accountInitials` to master's rail. At merge,
**keep this NavRail** (same parameter name and position as Lane 3's, so Lane 3's call site compiles) and drop
Lane 3's `RailAccount` / `accountInitials`; its `AccountInitialsTest` cases (two initials, null / blank → "G")
hold for this rail's `initials(name).ifEmpty { "G" }`.

## `ui/components/FocusMemory.kt` (`FocusRestorer`)
| Change | Why |
|---|---|
| `savedAtEntry`: the screen's `SavedFocus` (row, item key, index) captured when the restorer is created; the **first** `restore()` targets it, then clears it so later restores read the memory again. | After a pop the window regains focus and default focus lands on (e.g.) item 0 of the saved row, overwriting the memory before `restore()` runs; the screen then restored to the wrong item (review finding 5). |
| `focusIn(row, index)` is public (was private). | Search moves focus to the first result after Clear (§7.7). |

No change to `FocusMemory`, `rememberFocus`, `restoreRowFocus`, `entryIndex` results, or saved keys.

## Also touched outside `ui/nav`
- `MainActivity.kt`: provides `LocalSettings` (collected `Settings`) and wraps the app in `DesignCanvas` (§6 units).
- `ui/components/ExitDialog.kt`: dialog content wrapped in `DesignCanvas` (a Dialog is a new composition root, §6).
- `ui/components/Toasts.kt`: the pinned `Toaster` / `LocalToaster` / `ToastHost`, same API as Lane 4's;
  `ToastHost` here is only as large as the toast (Lane 4's fills the screen) — keep this one.
- `domain/LibraryState.kt`, `domain/LibraryActions.kt`: byte-identical to Lanes 2 and 4.
