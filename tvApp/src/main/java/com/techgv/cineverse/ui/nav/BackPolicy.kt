package com.techgv.cineverse.ui.nav

/**
 * BACK precedence (§1.2). Step 1 (modal open → close, focus to invoker) is owned by each modal's
 * `Dialog` window via `onDismissRequest`, so it never reaches the app-level handler.
 */
enum class BackAction { ShowExitDialog, Pop, FocusContent, ResetToHome, FocusHomeOrigin }

data class BackContext(
    val onLogin: Boolean,
    /** The current entry was pushed (there is an entry below it). */
    val hasPushedRoute: Boolean,
    val focusInRail: Boolean,
    val onHome: Boolean,
    val homeAtOrigin: Boolean,
)

fun backAction(c: BackContext): BackAction = when {
    c.onLogin -> if (c.hasPushedRoute) BackAction.Pop else BackAction.ShowExitDialog // Δ prototype
    c.focusInRail -> BackAction.FocusContent
    c.hasPushedRoute -> BackAction.Pop
    !c.onHome -> BackAction.ResetToHome
    !c.homeAtOrigin -> BackAction.FocusHomeOrigin
    else -> BackAction.ShowExitDialog
}
