package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.techgv.cineverse.ui.adaptive.LocalWindowClass
import com.techgv.cineverse.ui.adaptive.WindowClass
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens

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
        val gutter = if (LocalWindowClass.current == WindowClass.Phone) 16.dp else 32.dp
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columnWidth = ((maxWidth - gutter * 2).coerceAtLeast(0.dp)).coerceAtMost(1320.dp)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(320.dp),
                modifier = Modifier.width(columnWidth).fillMaxHeight().align(androidx.compose.ui.Alignment.TopCenter).testTag("web-settings"),
                contentPadding = PaddingValues(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item("title", span = { GridItemSpan(maxLineSpan) }) { Text("Settings", modifier = Modifier.semantics { heading() }) }
                if (storageFailure != null) item("storage", span = { GridItemSpan(maxLineSpan) }) {
                    WebStorageNotice(storageFailure, owner::retryStorage, Modifier.testTag("settings-storage-recovery"))
                }
                if (state.readFailure != null) item("read-error", span = { GridItemSpan(maxLineSpan) }) {
                    ErrorState("Preferences could not be read. Last observed values are retained; editing is unavailable.", owner::retryStorage, Modifier.testTag("settings-read-recovery"))
                }
                if (state.writeFailure != null) item("write-error", span = { GridItemSpan(maxLineSpan) }) {
                    Panel(title = "Preference write failed") {
                        Text("The edit was not confirmed. Retry reads to check stored values; the edit will not be replayed.")
                        PillButton("Retry reads", owner::retryStorage)
                        PillButton("Dismiss write error", owner::dismissWriteFailure, variant = ButtonVariant.Ghost)
                    }
                }
                if (state.pendingWrites > 0) item("pending", span = { GridItemSpan(maxLineSpan) }) { Text("Preference writes in progress") }
                val observed = state.settings
                if (observed == null) item("unknown", span = { GridItemSpan(maxLineSpan) }) { Text("Preferences are unknown. Waiting for a successful read.") }
                if (observed != null) {
                    item("look") {
                        Panel(Modifier.testTag("settings-look"), "Look and feel") {
                            val editable = state.readFailure == null && storageFailure == null
                            PreferenceToggle("Ambient glow", "Colour behind pages", Toggle.Glow, observed.glow, editable, owner)
                            PreferenceToggle("Reduce motion", "Limit interface animations", Toggle.Motion, observed.motion, editable, owner)
                            PreferenceToggle("Haptic taps on phones", "Supported phone browsers only", Toggle.Haptics, observed.haptics, editable, owner)
                            Text("Haptic strength · saved preference (web taps use a fixed 10 ms)")
                            PreferenceChoices(HapticStrength.entries.map { it.name }, observed.strength.name, editable, "strength") {
                                owner.selectStrength(HapticStrength.valueOf(it))
                            }
                        }
                    }
                    item("content") {
                        Panel(Modifier.testTag("settings-content"), "Content") {
                            val editable = state.readFailure == null && storageFailure == null
                            Text("Content language · language=${observed.language}")
                            PreferenceChoices(Settings.LANGUAGES, observed.language, editable, "language", { LANGUAGE_NAMES.getValue(it) + " · " + it }, owner::selectLanguage)
                            Text("Region · region=${observed.region}")
                            Text("Release dates and certifications")
                            PreferenceChoices(Settings.REGIONS, observed.region, editable, "region", { REGION_NAMES.getValue(it) + " · " + it }, owner::selectRegion)
                            PreferenceToggle("Include adult titles", "Search · include_adult=${observed.includeAdult}", Toggle.Adult, observed.includeAdult, editable, owner)
                        }
                    }
                }
                item("account") {
                    Panel(Modifier.testTag("settings-account"), "Account") {
                        when (session) {
                            WebSessionState.Checking -> Text("Checking account. Account actions are unavailable.")
                            is WebSessionState.Failed -> {
                                Text("Account verification failed. Account actions are unavailable.")
                                PillButton("Retry account check", retrySession)
                            }
                            is WebSessionState.Ready -> if (session.session == null) {
                                Text("Guest · preferences are saved on this browser")
                                PillButton("Sign in", owner::signIn)
                            } else {
                                Text(session.session.displayName)
                                PillButton("Sign out", owner::signOut)
                                PillButton("Import guest library", owner::importLibrary, variant = ButtonVariant.Ghost)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceToggle(title: String, description: String, toggle: Toggle, checked: Boolean, editable: Boolean, owner: WebSettingsOwner) {
    if (editable) SettingRow(title, description, Icons.Default.Settings, Tokens.Colors.Accent,
        SettingTrailing.Toggle(checked) { owner.setToggle(toggle, it) }, Modifier.testTag("settings-toggle-${toggle.name}"))
    else Text("$title · ${if (checked) "On" else "Off"} · editing unavailable")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceChoices(values: List<String>, selected: String, editable: Boolean, tag: String,
                              label: (String) -> String = { it }, select: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            if (editable) Chip(label(value), selected == value, { select(value) }, Modifier.testTag("settings-$tag-$value"))
            else Text(label(value) + if (selected == value) " · selected" else "")
        }
    }
}
