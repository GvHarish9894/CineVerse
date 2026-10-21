# Integration plan: lanes 4 and 2 (wave 2)

Base: `integration` at `2bd5a21` (master 78c14d1 + lane1-wave2 + lane3-wave2).
Dry runs: `git merge --no-commit --no-ff` on scratch branches `integration-dry4` (lane4-wave2 @ 4a3d6e9) and
`integration-dry2` (lane2-wave2 @ 1e4094e); both were discarded. Re-run the dry merge if a lane branch moves
after its review.

Trial resolutions (exactly as below) compiled on the scratch branches:
- lane 4 alone: `assembleDebug testDebugUnitTest assembleDebugAndroidTest` green, 154 unit tests.
- lane 2 alone: green after the `tmdbImageUrl` fix below, 153 unit tests.
- lane 2 then lane 4 on top: full gate green (incl. lint, 0 errors), 188 unit tests. The conflict set for the second
  merge is the same as merging it alone, so the order does not matter.

Rules carried over: Lane 1 owns the shell. Each domain file (`MediaRef.kt`, `Title.kt`, `Person.kt`,
`LibraryState.kt`, `LibraryActions.kt`) keeps one copy, the current integration version, and add/add is never
concatenated. `docs/IMPLEMENTATION_SPEC.md` merges cleanly in both lanes (no diff vs integration).

## lane4-wave2 (Library)
| File | Conflict | Intended resolution |
|---|---|---|
| `domain/MediaRef.kt` | add/add | Integration's copy. Lane 4's differs only in formatting and a doc comment; same `key`. |
| `domain/Title.kt` | add/add | Integration's copy. Lane 4's only lacks the doc comment; the fields are identical. |
| `ui/components/DesignCanvas.kt` | add/add | Integration's (Lane 1's real canvas). Drop Lane 4's pass-through stand-in, which Lane 4's MERGE_NOTES also asks for. |
| `ui/components/Toasts.kt` | add/add | Integration's (Lane 1's) file. Lane 4's `ToastHost(…, paused)` is not used. Player gating stays in AppNav (`if (!onPlayer) ToastHost(…)`), which behaves the same: the newest toast waits in the 1-slot queue and the showing toast is dropped. The live region and queue of 1 are the same in both files. No Lane 4 test calls `paused`. |
| `ui/components/FocusMemory.kt` | content (`focusIn`) | Union: Lane 1's **public** `focusIn` (Search needs it) with Lane 4's `lazyIndex` body. Keep Lane 4's `FocusRow.lazyIndices` (auto-merged). |
| `ui/nav/AppNav.kt` | content | Start from integration's file and add: (1) the `LibraryActionsHost(container.libraryRepository, container.appScope) { NavHost(…) }` wrap inside the content `Box`; (2) the `Route.Library` → `LibraryScreen(container, onOpenTitle, onExplore, onSignIn)` body; (3) `ImportLibraryHost(container.libraryRepository, container.libraryImporter, playerActive = onPlayer)` inside the root `Box`, under `LocalToaster`. Drop Lane 4's own `Toaster`, `ToastHost` and `Row` layout. |
| `MERGE_NOTES.md` | none (new file) | Merge its still-valid points into the SHELL_CHANGES.md merge log and delete the file. Its Toasts paragraph is stale: it refers to Lane 1's `Toast.kt` / `ToastState` (now deleted) and to keeping Lane 4's Toasts file. |

Auto-merged with no conflict: `CineVerseApp.kt` (library repository / importer in the container), `data/library/*`,
`data/tmdb/TmdbRepository.kt` (+5), `RateDialog.kt`, `ImportLibraryDialog.kt` (both already `DesignCanvas { }`),
`ui/library/*`, and the tests.

Decisions needed before the lane 4 merge (not merge mechanics):
- **D4-a FeedbackRoot in Rate / Import dialogs.** Lane 3's note (§9) says every Dialog should be
  `DesignCanvas { FeedbackRoot { … } }`. Lane 4's `RateDialog` / `ImportLibraryDialog` use only `DesignCanvas`, so
  D-pad keys in them get no haptics or sounds. Options: (a) add `FeedbackRoot` in both at merge, a 2-line
  follow-up commit; (b) leave it to Lane 4. Recommendation: (a).
- **D4-b LibraryPosterCard / TabChip.** Lane 4's MERGE_NOTES asks the integrator to replace them with Lane 1's
  `PosterCard` / `Chip`. That is a UI change, not merge mechanics. Options: (a) do it in the merge; (b) a
  Lane 4 follow-up after the merge. Recommendation: (b).

Post-merge checks: run instrumented `ui.*` tests, including Lane 4's `RateDialogTest`,
`ImportLibraryDialogTest` and `LibraryContentTest`. Then smoke on device: rail → My Library (guest empty
state; Explore resets to Movies), and a Home hero Watchlist toggle (now enabled through `LocalLibraryActions`)
with its toast. Library content starts at `padding(start = 48)`, which Lane 4 sized for master's `Row` rail. Inside
Lane 1's 88 inset that puts it at x 136, the same as Settings. Check it visually only; no change at merge.

## lane2-wave2 (Detail / Person / Season)
| File | Conflict | Intended resolution |
|---|---|---|
| `domain/MediaRef.kt` | add/add | Integration's copy (same as lane 4). |
| `domain/Title.kt` | add/add | Integration's copy (same as lane 4). |
| `ui/nav/AppNav.kt` | content | Start from integration's file and replace the three placeholder routes with `DetailScreen(it.toRoute(), container, onNavigate = { nav.navigate(it) })` / `PersonScreen(…)` / `SeasonScreen(…)`. `isRailScreen` is unchanged, so Detail, Person and Season get no rail. |
| `ui/components/DetailCardSurface.kt` | **semantic** (compiles clean as text, then fails) | Lane 2 declares `fun tmdbImageUrl(base: String, size: String, path: String?)` in `ui.components`, which clashes with Lane 1's `tmdbImageUrl(baseUrl, size, path)` in `TmdbImage.kt` (conflicting overloads, 12 call sites ambiguous). Delete Lane 2's copy and use Lane 1's. Both give the same URL: Lane 1 normalises the slashes; Lane 2's mappers already turn blank paths into null via `nonBlank()`, so Lane 2's blank check is not needed. |

Auto-merged with no conflict: `ui/components/FocusMemory.kt`. This is a **behaviour change in a shared file**: on a D-pad
entry into a row with nothing remembered, `onEnter` now lands on the row's first item instead of the
geometrically nearest one, while a programmatic `requestFocus` keeps its own target. Lane 1's rows
(Home / Browse / Search) and Lane 3's Settings are affected. Run all `ui.*` instrumented tests after the merge
and smoke Home row entry.
Also auto-merged: domain `Credit/Detail/Episode/Review/Season/Video.kt` (new), `data/tmdb/DetailMappers.kt`, the
cards (`CreditCard`, `FactCard`, `ReviewCard`, `SeasonCard`, `DetailCardSurface`), the screens and the tests.

Noted, not changed at merge (duplicates, not conflicts): Lane 2's `initialsOf` duplicates Lane 1's `initials`,
and `DetailCardSurface`'s image box duplicates `TmdbImage`. Lane 2 reads reduce-motion through
`LocalFeedback.current.reduceMotion` and Lane 1 through `LocalSettings.current.motion`; both read the same settings
`State` in MainActivity, so they agree.

Post-merge checks: run instrumented `ui.*` tests, including Lane 2's `DetailScreenDpadTest`,
`PersonScreenDpadTest` and `SeasonScreenDpadTest`. Then smoke: Home card → Detail (no rail, BACK pops and
restores focus on the card), Detail → Person → BACK, Detail → Season (LEFT from episodes goes to the seasons
list, not a rail).

## Known pre-existing failure (not caused by any merge)
`ui.search.SearchDpadTest.everyClearMovesFocusToTheFirstResult` (Lane 1) fails on emulator-5554 at
`SearchDpadTest.kt:115`: focus is not on the first trending result 5 s after Clear. It fails the same way on 3 of 3
runs. The merge changed none of the files it uses (`ui/search`, `NavRail`, `FocusMemory`, `data/tmdb`).
Lane 1 never ran it on a device. This needs a Lane 1 fix; it is not part of the merges.
