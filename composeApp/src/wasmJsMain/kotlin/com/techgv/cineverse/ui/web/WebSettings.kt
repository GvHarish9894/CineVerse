package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.presentation.LANGUAGE_NAMES
import com.techgv.cineverse.presentation.REGION_NAMES
import com.techgv.cineverse.presentation.Toggle
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle

/** Unconnected component. Host owns account dialogs and the repository/runtime identity. No saved scalars. */
@Composable
fun WebSettings(
    entry: WebEntry,
    repository: SettingsRepository,
    runtime: WebRuntime,
    onSignIn: () -> Unit,
    onSignOut: (AuthSession) -> Unit,
    onImport: (AuthSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val signIn by rememberUpdatedState(onSignIn)
    val signOut by rememberUpdatedState(onSignOut)
    val importLibrary by rememberUpdatedState(onImport)
    val owner = remember(entry, repository, runtime) {
        WebSettingsOwner(repository, runtime.settingsHolder, scope, runtime.session, runtime::retryStorage,
            { signIn() }, { signOut(it) }, { importLibrary(it) })
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val session by runtime.session.collectAsState()
    val storage by runtime.storageFailure.collectAsState()
    WebSettings(owner, session, storage, runtime::revalidateSession)
}

/** Direct test/host seam with an explicitly owned real holder/repository owner, not service location. */
@Composable
fun WebSettings(
    owner: WebSettingsOwner,
    session: WebSessionState,
    storageFailure: BrowserStorageFailure?,
    retrySession: () -> Unit,
) {
    val state by owner.state.collectAsState()
    ProvideWindowClass(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gutter = contentGutter(ShellStyle.Web, maxWidth)
            LazyVerticalGrid(
                // Kit minmax(340) → 300: three panels side by side from a 1024 window, so Account stays above the fold.
                columns = GridCells.Adaptive(300.dp),
                modifier = Modifier.fillMaxSize().testTag("web-settings"),
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item("title", span = { GridItemSpan(maxLineSpan) }) {
                    WebPageTitle("Settings", Modifier.padding(bottom = 10.dp), subtitle = "How CineVerse looks, feels and what it shows you")
                }
                if (storageFailure != null) item("storage", span = { GridItemSpan(maxLineSpan) }) {
                    WebStorageNotice(storageFailure, owner::retryStorage, Modifier.testTag("settings-storage-recovery"))
                }
                if (state.readFailure != null) item("read-error", span = { GridItemSpan(maxLineSpan) }) {
                    ErrorState("Preferences could not be read. Last observed values are retained; editing is unavailable.", owner::retryStorage, Modifier.testTag("settings-read-recovery"))
                }
                if (state.writeFailure != null) item("write-error", span = { GridItemSpan(maxLineSpan) }) {
                    Panel(title = "Preference write failed") {
                        Text("The edit was not confirmed. Retry reads to check stored values; the edit will not be replayed.", style = bodyStyle(15.sp))
                        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("Retry reads", owner::retryStorage, small = true)
                            PillButton("Dismiss write error", owner::dismissWriteFailure, variant = ButtonVariant.Ghost, small = true)
                        }
                    }
                }
                if (state.pendingWrites > 0) item("pending", span = { GridItemSpan(maxLineSpan) }) { Caption("Preference writes in progress") }
                val observed = state.settings
                if (observed == null) item("unknown", span = { GridItemSpan(maxLineSpan) }) { Caption("Preferences are unknown. Waiting for a successful read.") }
                if (observed != null) {
                    item("look") {
                        Panel(Modifier.testTag("settings-look"), "Look and feel") {
                            val editable = state.readFailure == null && storageFailure == null
                            PreferenceToggle("Ambient glow", "Colour behind pages", Toggle.Glow, observed.glow, editable, owner)
                            PreferenceToggle("Reduce motion", "Limit interface animations", Toggle.Motion, observed.motion, editable, owner, divider = true)
                            PreferenceToggle("Haptic taps on phones", "Supported phone browsers only", Toggle.Haptics, observed.haptics, editable, owner, divider = true)
                            PreferenceBlock("Haptic strength", "Saved preference · web taps use a fixed 10 ms") {
                                PreferenceChoices(HapticStrength.entries.map { it.name }, observed.strength.name, editable, "strength") {
                                    owner.selectStrength(HapticStrength.valueOf(it))
                                }
                            }
                        }
                    }
                    item("content") {
                        Panel(Modifier.testTag("settings-content"), "Content") {
                            val editable = state.readFailure == null && storageFailure == null
                            PreferenceBlock("Content language", null, "language=${observed.language}", divider = false) {
                                // Latin name only: the bundled fonts have no Devanagari/Tamil/Malayalam glyphs (tofu).
                                PreferenceChoices(Settings.LANGUAGES, observed.language, editable, "language", { LANGUAGE_NAMES.getValue(it).substringAfterLast(" · ") + " · " + it }, owner::selectLanguage)
                            }
                            PreferenceBlock("Region", "Release dates and certifications", "region=${observed.region}") {
                                PreferenceChoices(Settings.REGIONS, observed.region, editable, "region", { REGION_NAMES.getValue(it) + " · " + it }, owner::selectRegion)
                            }
                            PreferenceToggle("Include adult titles", "Search · include_adult=${observed.includeAdult}", Toggle.Adult, observed.includeAdult, editable, owner, divider = true)
                        }
                    }
                }
                item("account") {
                    Panel(Modifier.testTag("settings-account"), "Account") {
                        when (session) {
                            WebSessionState.Checking -> Caption("Checking account. Account actions are unavailable.")
                            is WebSessionState.Failed -> {
                                Caption("Account verification failed. Account actions are unavailable.")
                                PillButton("Retry account check", retrySession, Modifier.padding(top = 14.dp), variant = ButtonVariant.Ghost, small = true)
                            }
                            is WebSessionState.Ready -> {
                                val account = session.session
                                if (account == null) {
                                    Text("Guest · preferences are saved in this browser", style = bodyStyle(15.5.sp, FontWeight.SemiBold))
                                    val trigger = rememberWebModalTrigger(WebModalKind.Approval)
                                    PillButton("Sign in", { trigger.open(owner::signIn) }, trigger.modifier.padding(top = 14.dp), small = true)
                                } else {
                                    Text("Signed in as ${account.displayName}", style = bodyStyle(15.5.sp, FontWeight.SemiBold))
                                    FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        val signOutTrigger = rememberWebModalTrigger(WebModalKind.Account)
                                        val importTrigger = rememberWebModalTrigger(WebModalKind.Account)
                                        PillButton("Sign out", { signOutTrigger.open(owner::signOut) }, signOutTrigger.modifier, variant = ButtonVariant.Ghost, small = true)
                                        PillButton("Import guest library", { importTrigger.open(owner::importLibrary) }, importTrigger.modifier, variant = ButtonVariant.Ghost, small = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = bodyStyle(13.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.62f))

/** `.setrow` without a switch: title, optional description, the control, then the TMDB parameter as a small `.api` caption. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceBlock(title: String, description: String?, api: String? = null, divider: Boolean = true, control: @Composable () -> Unit) {
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, style = bodyStyle(15.5.sp, FontWeight.SemiBold))
            if (description != null) Caption(description, Modifier.padding(top = 2.dp))
        }
        control()
        if (api != null) Text(api, style = bodyStyle(12.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.8f),
            modifier = Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun PreferenceToggle(title: String, description: String, toggle: Toggle, checked: Boolean, editable: Boolean, owner: WebSettingsOwner, divider: Boolean = false) {
    if (editable) SettingRow(title, description, when (toggle) {
        Toggle.Glow -> CvIcons.Glow
        Toggle.Motion -> CvIcons.Motion
        Toggle.Haptics -> CvIcons.Vibrate
        Toggle.Adult, Toggle.Sounds -> CvIcons.Globe
    }, Tokens.Colors.Accent,
        SettingTrailing.Toggle(checked) { owner.setToggle(toggle, it) }, Modifier.testTag("settings-toggle-${toggle.name}"), showDivider = divider)
    else Caption("$title · ${if (checked) "On" else "Off"} · editing unavailable", Modifier.padding(vertical = 10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceChoices(values: List<String>, selected: String, editable: Boolean, tag: String,
                              label: (String) -> String = { it }, select: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            if (editable) Chip(label(value), selected == value, { select(value) }, Modifier.testTag("settings-$tag-$value"))
            else Caption(label(value) + if (selected == value) " · selected" else "")
        }
    }
}
