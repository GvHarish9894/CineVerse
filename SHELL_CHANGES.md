# Lane 3 (Login / Settings / Feedback / About): changes to shared shell files

Lane 1 owns the shell: overlay rail (88 → 268 design units), Clock, DesignCanvas in MainActivity, and
`NavRail(current, onSelect, accountName: String?, modifier)` with initials computed inside NavRail. At
merge, start from Lane 1's versions of these files and re-apply only the items marked **keep**.
Lane 3 does not change BACK precedence (`BackHandler` / `backAction` are untouched) or the rule that
Login has no rail (`isRailScreen` is untouched).

## MainActivity.kt
- **keep:** wrap the app content in `ProvideFeedback(settingsState) { … }` (ui/feedback/Feedback.kt).
  `settingsState` is `appContainer.settingsRepository.settings.collectAsStateWithLifecycle(Settings())`
  kept as a `State` (not `by`), because `AndroidFeedback` reads it whenever a key is pressed. Lane 1
  collects the same flow for `LocalSettings`. Use one `State` for both
  (`LocalSettings provides settingsState.value`). Put `ProvideFeedback` inside `DesignCanvas`/theme,
  around `Surface { AppNav(...) }`, and install it once only: it also turns off the view's own sound
  effects while in-app sounds are on (§9).

## ui/nav/AppNav.kt
- **keep:** the §9 D-pad hook goes once on the main window's root that contains both the rail and the
  content: `.navigationFeedback(LocalFeedback.current, LocalFocusManager.current)`. On this branch it
  is on the `Row`; in Lane 1's layout it goes on the outer `Box`. Don't add it anywhere else in the
  main window, or OK would give feedback twice.
- **keep:** the route bodies:
  `composable<Route.Settings> { SettingsScreen(onSignIn = { nav.navigate(Route.Login) }) }` and
  `composable<Route.Login> { LoginScreen(onContinueAsGuest = leaveLogin, onSignedIn = leaveLogin, message = loginMessage) }`.
  The screens show their own toasts through `LocalToaster`.
- **take Lane 1's:** `credentials` collected and passed as `NavRail(accountName = credentials?.displayName)`.
  Lane 1 already does this.
- **take one copy:** `val toaster = remember { Toaster() }` +
  `CompositionLocalProvider(LocalToaster provides toaster)` around the screens, plus
  `ToastHost(toaster)`, which is never shown on Player (`destination?.hasRoute<Route.Player>() != true`).
  Lane 1, Lane 3 and Lane 4 each add the same seam; keep exactly one provider and one host.

## ui/components/Toasts.kt (pinned §6 seam)
- Lane 3's file is a byte-for-byte copy of Lane 4's: `Toaster.show(title, subtitle?)`, `LocalToaster`,
  `ToastHost(toaster, modifier)`. Lane 1's copy has the same public shape, but its `ToastHost` is only
  as large as the toast and the caller places it
  (`Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp)`), while Lane 4's fills the screen
  and aligns itself to the bottom. Keep one file, and make the AppNav call match the `ToastHost` you
  keep.

## ui/nav/NavRail.kt
- Lane 3 added `accountName: String?` (same position and type as Lane 1's) and a small bottom
  `RailAccount` (initials circle + name/"Guest") with `accountInitials()`.
- **take Lane 1's NavRail.** Drop Lane 3's `RailAccount` and `accountInitials`. Then either delete
  `AccountInitialsTest`, or point it at Lane 1's `ui/components/initials`. No other rail spacing or
  child changes remain on this branch.

## ui/components/ExitDialog.kt
- **keep:** the dialog content is wrapped in `FeedbackRoot { … }`. A `Dialog` is its own window, so
  AppNav's hook never sees its keys. If Lane 1/4 wrap the dialog in `DesignCanvas { … }`, nest the
  two as `DesignCanvas { FeedbackRoot { … } }`. Rate/Import dialogs should do the same.

## Other shared (non-shell) changes
- `data/auth/AuthRepository.kt`:
  - `SignInOutcome.SignedIn` now also carries `account: AccountDto`.
  - `signOut(onSignedOutLocally: () -> Unit = {})` calls back after the local clear and before remote
    revocation. The default keeps existing callers unchanged.
- `data/settings/SettingsRepository.kt`: account-defaults offers are stored per `account_object_id`:
  - `pendingAccountDefaults` holds the open offers;
  - `offerAccountDefaults(defaults)` adds one;
  - `resolveAccountDefaults(accountObjectId, apply)` closes one.
