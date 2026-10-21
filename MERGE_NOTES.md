# Lane 4 (Library) merge notes

- **Toasts:** keep Lane 4's `ui/components/Toasts.kt` (`Toaster`, `LocalToaster`, `ToastHost`, pinned in §6). Discard
  Lane 1's `Toast.kt` (`ToastState` / `LocalToasts`) and Lane 3's `AccountToast`; their callers switch to
  `LocalToaster.current.show(title, subtitle?)`. `ToastHost(toaster, paused = playerActive)` queues while the Player is up.
- **DesignCanvas:** keep Lane 1's real `ui/components/DesignCanvas.kt` (drop Lane 4's pass-through stand-in) and
  keep Lane 4's `DesignCanvas { }` wrapping inside `RateDialog` and `ImportLibraryDialog`.
- **Cards / chips:** at merge, replace the lane-local `ui/library/LibraryPosterCard.kt` and the private `TabChip` in
  `LibraryScreen.kt` with Lane 1's shared PosterCard / Chip. They must honour reduce motion and ambient glow
  (§6 focus rules), and the grid must keep its PinToStart scrolling.
- **AppNav:** `playerActive` (current destination is `Route.Player`) feeds `ToastHost(paused = …)` and
  `ImportLibraryHost(playerActive = …)`; keep both if another lane rewrites the AppNav scaffold.
