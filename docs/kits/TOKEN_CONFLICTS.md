# Token and spec conflicts (P7 design system)

Where the two kits, `design/*/tokens.json`, the prototypes and the TV spec disagree, this is what `composeApp` does.
Supervisor-approved items are marked ✔. Code: `composeApp/src/commonMain/kotlin/com/techgv/cineverse/ui/`.

| Value | Source A | Source B | Chosen | Why |
|---|---|---|---|---|
| Haptic strength base | `tokens.json` `haptics.strengthMs` 6 / 12 / 20 | mobile `INTERACTIONS.md` + prototype `hap()` 6 / 10 / 16 | 6 / 10 / 16 (`HapticStrength`) ✔ | The mobile kit is the authority for the mobile Haptics API; the JSON values are still generated verbatim as `Tokens.Haptics.StrengthMs` (data only). |
| Haptic events | `tokens.json`: tick, edge, confirm, back `[b,30,b]`, success, toggle (TV-flavoured) | mobile kit: tap, tick, select, toggle, success, warn, back `[b]` | Mobile kit set (`HapticKind`) ✔; toggle split into `ToggleOn`/`ToggleOff` | Android 14 has distinct TOGGLE_ON / TOGGLE_OFF constants; patterns identical. JSON patterns stay generated as `Tokens.Haptics.*`. |
| Web haptics | Brief: wasm no-op | web `INTERACTIONS.md`: `navigator.vibrate(10)` on save/rate/toggle, phones only | `vibrate(10)` for Success/ToggleOn/ToggleOff when `navigator.vibrate` exists and the pointer is coarse; otherwise no-op; never throws ✔ | Kit behaviour, feature-detected. |
| Kit token files | `design/mobile/tokens.json` | `design/web/tokens.json` | Must stay identical; the generator fails on divergence ✔ | They are byte-identical today and already namespace per platform (`scaleMobile`/`scaleWeb`, `mobilePoster`/`webPoster`, glow opacities). |
| Score ring without votes | Prototypes: grey "NR" ring (`scoreNone`) | TV spec §6 / brief: hidden at `vote_count` 0 | Hidden; `scoreNone` token kept for P8/P9 text uses | Same rule on every platform. |
| Score ring on poster cards | Mobile prototype `.pc`: no ring (meta line "2026 · ★ 8.1") | Web `.card`: 40px ring overlapping the art | `showScoreRing` defaults to the shell (web on, mobile off) | Each kit's own look; callers can override. |
| Keyboard focus outline | Mobile prototype: 2px, offset 2px | Web prototype + brief: 2px white, offset 3px | 2px white, 3px offset everywhere (`Modifier.focusRing`) | Brief. |
| Ambient glow | Mobile: c1 70%/30%, c2 20%/70%, 0.60 × strength, blur 70, 900 ms | Web: c1 72%/22%, c2 14%/60%, top 1100px, 0.45 × strength, blur 90, 1 s | Shell picks (`AmbientGlow`); no blur pass | The radial gradients already fade to transparent; skipping blur gives the same result on Android < 12 (where `Modifier.blur` is a no-op) and is cheap on web. |
| Segmented control | Mobile: accent pill slides with an overshoot spring, radius 14/10, 34dp | Web: fully rounded, 32dp, background switch (no slide) | One primitive: sliding accent spring pill; web shape/height | Brief asks for the sliding pill; reduce motion snaps. |
| Glass / backdrop blur | Prototypes: `backdrop-filter: blur(...)` on bars, sheets, dialogs | Compose has no backdrop blur | Translucent fills only: top bar rgba(12,10,24,.8), tab bar `surfaceGlass`; scrims without blur | Accepted limit (L3 review): "glass" is a translucent approximation on every platform until Compose offers a backdrop blur. |
| Dialog scrim | Web kit: rgba(5,4,12,.72) + 12px blur | Android dialog window dim | iOS + web: kit colour via `DialogProperties.scrimColor`; Android: platform window dim | Android's `DialogProperties` has no scrim colour. |
| Round button labels / cursor | Web prototype: `title` attribute tooltips, `cursor:pointer` | Compose has no `title` attribute | Web shell: `RoundIconButton` shows its `contentDescription` in an M3 plain tooltip on hover; every `cvClickable` sets the hand pointer icon (no-op without a pointer) | Kit behaviour with platform equivalents. |
| Top bar height | `scrim.top`: 130px (TV) / 100px (mobile) | Mobile `.bar`: 100pt = 48 status + 52 | Status-bar inset + 52dp | Real insets instead of the prototype's fixed 48. |

## Fonts

Static instances from the Google Fonts CSS API (`fonts.googleapis.com/css2?family=Bricolage+Grotesque:wght@500;700;800&family=Inter:wght@400;500;600;700`,
default optical size), SIL Open Font License 1.1 (`composeApp/licenses/OFL-*.txt`), downloaded 2026-10-22. Recorded in `docs/TOOLCHAIN.md`.

## Browser acceptance

Gallery (`wasmJsBrowserDistribution` production bundle, plus the dev server for Chrome), checked 2026-10-23:

- **Chrome**: production and development builds render with a clean console at 1440 and 390 wide (only WebGL driver info lines).
- **Firefox 158** (headless): renders with a clean console.
- **Safari**: the production bundle loads and runs. Safari fetched both `.wasm` modules and then the font and
  `sample-tmdb.json` resources that the running Kotlin code requests. We have no accessibility-tree dump, no keyboard
  events and no screenshot, because macOS blocked automation from the agent host: Apple Events to System Events
  timed out (-1712) behind unanswered consent prompts, `AXIsProcessTrusted()` is false, and Screen Recording is
  denied (`screencapture` could not create an image). The console is also unavailable, because Safari's remote
  automation is a user setting and stays off. No setting or prompt was changed. Keyboard behaviour (Tab order, Esc
  on dialog and sheet, focus return) is covered by the headless-browser Compose UI tests (`DialogFocusTest`,
  `SheetDismissTest`). To check Safari by hand: Tab through the gallery, open the dialog and press Esc, then check
  that focus is back on "Open dialog".
