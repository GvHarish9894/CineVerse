package com.techgv.cineverse.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * Full-screen root of a screen under AppNav, as one focus group. AppNav wraps its content in `focusRestorer()`,
 * which has no focus target of its own: its onEnter redirect then applies to every top-level focus group inside
 * the screen, so a programmatic `requestFocus()` into an inner group (e.g. the Season episode panel's Close)
 * was sent to the last focused item elsewhere (a season). With this root the restorer sees one group: the
 * screen, and focus moves inside it are left alone.
 */
fun Modifier.screenFocusRoot(): Modifier = fillMaxSize().focusGroup()
