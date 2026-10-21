# Lane 4 (Library) merge notes

- **Toasts:** Lane 1 and Lane 3 already use the canonical `ui/components/Toasts.kt` (`Toaster`, `LocalToaster`,
  `ToastHost`, pinned in §6). Keep one `Toasts.kt`: merge Lane 1's route-gated, bounded `ToastHost` with this
  branch's `paused` (queued while `playerActive`) behaviour — combine the features in that one file, not two files.
- **FocusMemory.kt:** keep one file carrying all three deltas:
  - Lane 1: the initial snapshot and the public `focusIn`;
  - Lane 2: directional first entry;
  - Lane 4: `FocusRow.lazyIndices` (optional, default null) — the Library grid restores by lazy index.
- **Dialogs:** `RateDialog` and `ImportLibraryDialog` use the real `DesignCanvas` (Lane 1's
  `ui/components/DesignCanvas.kt`; drop Lane 4's pass-through stand-in) and Lane 3's `FeedbackRoot`
  (`ui/feedback/Feedback.kt`), as `ExitDialog` does.
- **Cards / chips:** replace the lane-local `ui/library/LibraryPosterCard.kt` and the private `TabChip` in
  `LibraryScreen.kt` with Lane 1's shared cards (PosterCard / Chip). They must honour reduce motion and ambient glow
  (§6 focus rules), and the grid must keep its PinToStart scrolling.
- **AppNav:** `playerActive` (current destination is `Route.Player`) feeds `ToastHost(paused = …)`,
  `LibraryActionsHost(playerActive = …)` (the Rate dialog waits while the Player is up) and
  `ImportLibraryHost(playerActive = …)`; keep all three if another lane rewrites the AppNav scaffold.
