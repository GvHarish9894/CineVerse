# Web interactions: hover, focus, scrims, glow, motion

## Hover & focus
| Element | Hover / keyboard focus |
|---|---|
| Poster card | translateY −6px + scale 1.03 (300ms emphasized), glow `0 26 60 −18 c1` + 2px white ring, score ring rises with it, quick-action button fades/scales in |
| Person card | lift 5px + scale 1.03, coloured glow |
| Video card | scale 1.03, play disc scales 1.12; on Home also swaps the trailers section background |
| Nav link / menu item | white 8% fill |
| Round action buttons | rise 2px + tooltip (white, dark text) |
| Buttons | brightness 1.08 on hover, scale 0.97 on press |
| Hero | pointer inside pauses the 8s auto-advance and its progress bar |
| Keyboard | 2px white outline, 3px offset, on every interactive element |

Compose: `Modifier.onPointerEvent(PointerEventType.Enter/Exit)` or `hoverable(interactionSource)` + `collectIsHoveredAsState()`; combine hovered || focused into one `isActive` to drive `animateFloatAsState` for lift/scale and `animateColorAsState` for glow.

## Scrolling rows
Horizontal rows use native scroll (trackpad / shift-wheel) with snap, plus ←/→ buttons that scroll ~80% of the visible width smoothly (`LazyListState.animateScrollBy`). Rows start aligned with the 1320px column and bleed to the window edge.

## Haptics on the web
Only phone browsers support it: call `navigator.vibrate(10)` (via a tiny JS interop function) on save/rate/toggle when "Haptic taps on phones" is on. Desktop has nothing to do here — rely on motion and sound-free visual feedback.

## Scrims
- Home hero: left scrim (bg → 90% @28% → 45% @58% → 0 @82%) + bottom 55%.
- Detail hero band: horizontal bg 96% → 86% @38% → 55%, plus bottom fade into the page.
- Trailers section: dark 88% → 60% horizontal + top/bottom fades.
- Dialogs: 72% + 12px blur.
- Sticky header: glass 74% + 22px blur so content stays faintly visible underneath.

## Ambient glow
Two radial gradients (c1 at 72%/22%, c2 at 14%/60%), blur 90px, opacity 0.45 × strength, top 1100px of the page; crossfade 1s when the featured title changes. Hovered cards add their own coloured glow.

## Motion
Page enter: fade + 12px rise, 450ms. Hero change: backdrop crossfade 800ms (scale 1.04 → 1) + text fade-up 500ms. Dropdowns: 180ms. Dialogs: 350ms rise + scale 0.97 → 1. Toast: 350ms. Reduce motion (setting or `prefers-reduced-motion`) disables all of these.

## Keyboard map
Tab / Shift+Tab move focus · Enter/Space activate · Esc closes menu/dialog · `/` focuses search · ←/→ inside the rating stars change by 0.5.
