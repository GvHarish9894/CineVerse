# Mobile interactions: haptics, scrims, glow, motion

## Haptics (both platforms through one `Haptics` interface)

| Event | Prototype pattern (ms) | Android | iOS |
|---|---|---|---|
| tap (card, row, button) | `[base]` | `KEYBOARD_TAP` / `VIRTUAL_KEY` | `UIImpactFeedbackGenerator(.light)` |
| tick (carousel page, slider step, segment) | `[base]` | `CLOCK_TICK` / `SEGMENT_TICK` (API 34) | `UISelectionFeedbackGenerator` |
| select (tab, primary action) | `[base+4]` | `CONTEXT_CLICK` | `.medium` impact |
| toggle (switch) | `[base,30,base]` | `TOGGLE_ON` / `TOGGLE_OFF` (API 34) | `.rigid` impact |
| success (saved, rated, signed in) | `[base+4,50,base*2]` | `CONFIRM` (API 30) | `UINotificationFeedbackGenerator(.success)` |
| warn (sign out, destructive) | `[base*2,60,base*2]` | `REJECT` (API 30) | `.warning` |
| back | `[base]` | `VIRTUAL_KEY` | `.soft` impact |

`base` = 6 / 10 / 16 ms (Light / Medium / Strong). On Android prefer `performHapticFeedback` constants (they respect the user's system setting); use `VibrationEffect` only for the strength variants. Disabled entirely when the Haptics toggle is off.

## Press feedback
Every tappable scales to 0.95 in 160ms on press (`Modifier.graphicsLayer` driven by `interactionSource.collectIsPressedAsState()`), springs back on release. Tab icons scale to 0.88.

## Scrims
- Hero slide: bottom gradient bg 100% → 86% @30% → 0 @60%, plus top 50% → 0 @22% under the top bar.
- Detail header: same bottom fade + top fade 55%.
- Top bar: when transparent, a 130pt gradient scrim behind it; when solid, glass.
- Tab bar: 140pt bottom scrim behind the floating bar.
- Sheets: scrim 62% + 6dp blur; sheet slides up 400ms.
- Poster titles: bottom gradient to black 62%.

## Ambient glow
Two radial gradients (c1 at 70%/30%, c2 at 20%/70%), blur 70dp, opacity 0.6 × strength, behind the scroll content (not scrolling with it). Crossfade 900ms on change. On Android < 12 `Modifier.blur` is a no-op — draw a pre-blurred 64px bitmap scaled up instead.

## Scroll behaviour
- Top bar turns solid after: Home 420pt, Detail 250pt, Person 200pt, others 60pt.
- Detail tabs stick under the top bar.
- Each tab keeps its own back stack and scroll position; pushing a screen restores scroll on Back.
- Carousel: paging snap, current page drives the ambient glow and a tick haptic.
- Horizontal rows: snap-to-start, 20pt content padding.

## Motion
Screen enter: fade + 14pt rise, 380ms. Segment pill: spring (overshoot). Sheet: 400ms emphasized-decelerate. Toggle knob: spring. Toast: fade + 16pt rise. Reduce motion (app or OS) → crossfades only.

## Gestures
Back: Android predictive back / iOS edge swipe pops the stack. Sheet: drag down to dismiss. Player: swipe down to close.
