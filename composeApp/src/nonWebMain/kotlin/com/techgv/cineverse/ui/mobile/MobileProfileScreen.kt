package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.presentation.LANGUAGE_NAMES
import com.techgv.cineverse.presentation.REGION_NAMES
import com.techgv.cineverse.presentation.Toggle
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.displayStyle
import kotlinx.coroutines.flow.distinctUntilChanged

/** Root activates the entry; callbacks MUST open the root's real sign-in/account-confirmation flow. */
@Composable
internal fun MobileProfileScreen(
    model: MobileProfileViewModel,
    onSignIn: () -> Unit,
    onSignOut: (AuthSession) -> Unit,
) {
    val snapshot by model.state.collectAsState()
    val state = model.visibleState(snapshot)
    DisposableEffect(model) { onDispose { model.setActive(false) } }
    key(model, state.context) {
        val token = state.context
        val position = remember { state.index to state.offset }
        val scroll = remember { LazyListState() }
        var baseline by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var changed by remember { mutableStateOf(false) }
        val restored = baseline != null
        val ready = !state.invalid && state.settings != null && !state.readFailed && !state.writeFailed &&
            (state.identity != null || state.identityFailed)
        val persist by rememberUpdatedState(ready && restored)
        LaunchedEffect(scroll, ready) {
            if (ready && !restored) {
                // Restore only against the actual preference/identity topology, never a loading shell.
                scroll.scrollToItem(position.first, position.second)
                baseline = scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset
            }
        }
        LaunchedEffect(scroll, ready, restored) {
            if (persist) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                .distinctUntilChanged().collect { current ->
                    if (persist && (changed || current != baseline)) {
                        changed = true
                        model.saveScroll(token, current.first, current.second)
                    }
                }
        }
        DisposableEffect(scroll) {
            onDispose {
                val current = scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset
                if (persist && (changed || current != baseline)) model.saveScroll(token, current.first, current.second)
            }
        }
        ProfileContent(state, scroll, { model.retryReads(token) }, { model.retryIdentity(token) },
            { model.signIn(token, onSignIn) }, { model.signOut(token, it, onSignOut) },
            { toggle, target -> model.setToggle(token, toggle, target) },
            { state.settings?.let { model.cycleStrength(token, it.strength) } },
            { model.openPicker(token, it) })
        val picker = state.picker
        val preferences = state.settings
        if (ready && picker != null && preferences != null) MobilePreferenceSheet(picker, preferences,
            { model.select(token, picker, it) }, { model.dismissPicker(token) })
    }
}

@Composable
private fun ProfileContent(
    state: MobileProfileState,
    scroll: LazyListState,
    retryReads: () -> Unit,
    retryIdentity: () -> Unit,
    signIn: () -> Unit,
    signOut: (AuthSession) -> Unit,
    toggle: (Toggle, Boolean) -> Unit,
    strength: () -> Unit,
    openPicker: (MobilePreferencePicker) -> Unit,
) {
    // Like the other tabs: the glow runs under the status bar; only the title is pushed below it.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        AmbientGlow(mobilePalette("profile"))
        LazyColumn(Modifier.fillMaxSize().testTag("mobile-profile"), state = scroll,
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("title") { Text("Profile", Modifier.statusBarsPadding(), style = displayStyle(34.sp)) }
            item("identity") {
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            state.invalid -> Text("Invalid saved profile state. Leave this entry to return.")
                            state.identityFailed -> {
                                Text("Account verification unavailable. Account actions are disabled.")
                                PillButton("Retry account check", retryIdentity, small = true)
                            }
                            state.identity == null -> Text("Checking account…")
                            state.identity.session == null -> {
                                Text("Guest", style = displayStyle(24.sp))
                                Text("Not signed in · library stays on this device")
                                PillButton("Sign in", signIn, small = true)
                            }
                            else -> {
                                val session = state.identity.session
                                Text(session.displayName, style = displayStyle(24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("TMDB account connected")
                                PillButton("Sign out", { signOut(session) }, variant = ButtonVariant.Ghost, small = true)
                            }
                        }
                    }
                }
            }
            item("storage") {
                when {
                    state.invalid -> Text("Preferences are unavailable for this entry.")
                    state.readFailed -> ErrorState("Saved preferences unavailable. Retry reads; no edits will be replayed.", retryReads,
                        Modifier.testTag("profile-read-error"))
                    state.writeFailed -> ErrorState("Preference edit was not confirmed. Retry reads to check stored values; the edit will not be replayed.", retryReads,
                        Modifier.testTag("profile-write-error"))
                    state.settings == null -> Text("Loading saved preferences…")
                    else -> Text("Preferences on this device")
                }
            }
            val settings = state.settings
            if (settings != null && !state.invalid && !state.readFailed && !state.writeFailed) {
                item("feel-title") { SectionTitle("Look and feel") }
                item("feel") {
                    SettingsGroup {
                        ProfileToggle("Haptic feedback", "Taps, toggles and selections", Toggle.Haptics, settings, toggle)
                        SettingRow("Haptic strength", "Tap to cycle · Light, Medium, Strong", CvIcons.Vibrate, Tokens.Colors.Accent,
                            SettingTrailing.Value(settings.strength.name, strength),
                            Modifier.semantics { stateDescription = settings.strength.name }, showDivider = true)
                        ProfileToggle("Sounds", "Interface sounds", Toggle.Sounds, settings, toggle, true)
                        ProfileToggle("Ambient glow", "Screens pick up the poster’s colours", Toggle.Glow, settings, toggle, true)
                        ProfileToggle("Reduce motion", "Limit interface animations", Toggle.Motion, settings, toggle, true)
                    }
                }
                item("content-title") { SectionTitle("Content") }
                item("content") {
                    SettingsGroup {
                        SettingRow("Content language", "Titles and overviews", CvIcons.Globe, Tokens.Colors.Accent,
                            SettingTrailing.Value(profileLanguageLabel(settings.language)) { openPicker(MobilePreferencePicker.Language) },
                            Modifier.semantics { stateDescription = profileLanguageLabel(settings.language) })
                        SettingRow("Region", "Release dates and certifications", CvIcons.Globe, Tokens.Colors.Accent,
                            SettingTrailing.Value(REGION_NAMES.getValue(settings.region)) { openPicker(MobilePreferencePicker.Region) },
                            Modifier.semantics { stateDescription = REGION_NAMES.getValue(settings.region) }, showDivider = true)
                        ProfileToggle("Include adult titles", "Search results · off by default", Toggle.Adult, settings, toggle, true)
                    }
                }
            }
            item("attribution") { Footer() }
        }
    }
}

@Composable
private fun ProfileToggle(title: String, description: String, toggle: Toggle, settings: Settings,
    onToggle: (Toggle, Boolean) -> Unit, divider: Boolean = false) {
    SettingRow(title, description, when (toggle) {
        Toggle.Haptics -> CvIcons.Vibrate
        Toggle.Glow -> CvIcons.Glow
        Toggle.Motion -> CvIcons.Motion
        Toggle.Sounds, Toggle.Adult -> CvIcons.Globe
    }, Tokens.Colors.Accent, SettingTrailing.Toggle(settings.profileToggle(toggle)) { onToggle(toggle, it) }, showDivider = divider)
}

// The bundled typefaces lack Indic glyphs; retain the readable Latin name and the actual code.
internal fun profileLanguageLabel(code: String) = LANGUAGE_NAMES.getValue(code).substringAfterLast(" · ") + " · " + code
