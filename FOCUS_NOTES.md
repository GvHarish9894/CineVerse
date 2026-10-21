# Focus notes for the integrator (Lane 2)

## AppNav `focusRestorer()` hijacks focus requests into inner focus groups

AppNav wraps the screen content in `Modifier.focusRestorer()`. That modifier has no focus target of its own,
so its `onEnter` redirect applies to every top-level focus group inside the screen. A programmatic
`requestFocus()` into an inner group is then sent to the last focused item elsewhere. Seen on device: opening
the Season episode panel sent focus from its Close button back to the last focused season.

Fix on the Lane 2 branch: `ui/components/ScreenFocusRoot.kt` → `Modifier.screenFocusRoot()`
(`fillMaxSize().focusGroup()`), the root modifier of Detail, Person and Season. The restorer then sees one
group, the screen, and leaves focus moves inside it alone.

**Integration action, pick one:**
- apply `Modifier.screenFocusRoot()` to the root of every screen under AppNav (Home / Browse / Search /
  Settings / Library), or
- make AppNav's content `focusRestorer()` own an explicit `focusGroup()`
  (`Modifier.focusRestorer().focusGroup()` on the content container), so it never acts on inner groups.

`SeasonScreenDpadTest` wraps the screen in `focusRestorer()` the way AppNav does, so it catches a regression.

## Row entry without memory lands on the first card

`FocusRestorer.restoreRowFocus(row, scope)`: a D-pad entry into a row with nothing remembered now lands on its
first card (prototype `rowMem`), not on the nearest card by position. A programmatic `requestFocus()`
(`FocusDirection.Enter`) keeps its own target. The decision is `FocusRestorer.entryKey` and is covered by a unit
test in `FocusMemoryTest`. This also changes Lane 1's rows.
