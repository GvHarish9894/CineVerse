package com.techgv.cineverse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.techgv.cineverse.appContainer
import com.techgv.cineverse.ui.about.APP_VERSION
import com.techgv.cineverse.ui.about.AboutPane
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.LocalToaster
import com.techgv.cineverse.ui.components.rememberSavedLazyListState
import com.techgv.cineverse.ui.feedback.Feedback
import com.techgv.cineverse.ui.feedback.LocalFeedback

internal const val PANEL = "settings"
internal const val DEFAULTS_ROW = "accountDefaults"
internal const val DEFAULTS_APPLY = "accountDefaults.apply"
internal const val DEFAULTS_DISMISS = "accountDefaults.dismiss"
private const val ABOUT = "about"

/**
 * One focus row per LazyColumn item, so a focus row's index is its lazy-item index (§1.3 restore scrolls
 * the list by it): the defaults offer (Apply, Dismiss) when shown, then one row per setting.
 */
internal fun settingsFocusRows(offerShown: Boolean, rowKeys: List<String>): List<FocusRow> =
    listOfNotNull(FocusRow(PANEL, DEFAULTS_ROW, listOf(DEFAULTS_APPLY, DEFAULTS_DISMISS)).takeIf { offerShown }) +
        rowKeys.map { FocusRow(PANEL, it, listOf(it)) }

private sealed interface RowValue {
    data class Toggle(val on: Boolean) : RowValue
    /** A ←→ value; OK steps forward too. */
    data class Choice(val text: String, val onCycle: (Int) -> Unit) : RowValue
    data class Label(val text: String) : RowValue
}

private class SettingRow(
    val key: String,
    val glyph: String,
    val label: String,
    val description: String,
    val value: RowValue,
    val onClick: () -> Unit = {},
)

/** §7.9 rows, labels and descriptions (prototype copy; Region without the watch-provider promise). */
private fun settingRows(
    state: SettingsUiState,
    vm: SettingsViewModel,
    onSignIn: () -> Unit,
): List<SettingRow> {
    val s = state.settings
    fun toggle(key: String, glyph: String, label: String, description: String, on: Boolean, toggle: Toggle) =
        SettingRow(key, glyph, label, description, RowValue.Toggle(on)) { vm.toggle(toggle) }
    fun choice(key: String, glyph: String, label: String, description: String, text: String, choice: Choice): SettingRow {
        val value = RowValue.Choice(text) { vm.cycle(choice, it) }
        return SettingRow(key, glyph, label, description, value) { value.onCycle(1) }
    }
    return listOf(
        toggle(
            "haptics", "≈", "Haptic feedback",
            "A short pulse on every focus move, a double pulse when you hit the edge of a row, and a firmer tap " +
                "when you press OK. Most TV remotes have no vibrator, so you may not feel anything.",
            s.haptics, Toggle.Haptics,
        ),
        choice(
            "strength", "≈", "Haptic strength",
            "Light 6 ms, Medium 12 ms or Strong 20 ms pulses. Use ← → to change.",
            s.strength.name, Choice.Strength,
        ),
        toggle(
            "sounds", "♪", "Navigation sounds",
            "Soft clicks on focus moves, a click when you press OK and a quieter click at the edge of a row.",
            s.sounds, Toggle.Sounds,
        ),
        toggle(
            "glow", "✦", "Ambient glow",
            "The room behind the UI picks up the colours of whatever is focused, and the focused card casts a coloured glow.",
            s.glow, Toggle.Glow,
        ),
        toggle("motion", "◌", "Reduce motion", "Turns off zoom, bounce, shake and cross-fade animations.", s.motion, Toggle.Motion),
        choice(
            "language", "文", "Content language",
            "Language for titles, overviews and artwork. Use ← → to change.",
            LANGUAGE_NAMES.getValue(s.language), Choice.Language,
        ),
        choice(
            "region", "⚑", "Region",
            "Sets release dates and certifications. Use ← → to change.",
            REGION_NAMES.getValue(s.region), Choice.Region,
        ),
        toggle(
            "adult", "⊘", "Include adult content",
            "Off by default. When on, adult titles can appear in search results.",
            s.includeAdult, Toggle.Adult,
        ),
        if (state.accountName != null) {
            SettingRow("account", "◉", "Sign out", "Ends your TMDB session on this TV. Your library stays on this TV.", RowValue.Label(state.accountName), vm::signOut)
        } else {
            SettingRow(
                "account", "◉", "Sign in with TMDB",
                "Approve CineVerse from your phone to sync your watchlist, favourites and ratings.",
                RowValue.Label("Guest"), onSignIn,
            )
        },
        SettingRow(ABOUT, "ⓘ", "About", "", RowValue.Label(APP_VERSION)),
    )
}

/** §7.9: rows on the left, live description pane on the right. */
@Composable
fun SettingsScreen(
    onSignIn: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(LocalContext.current.appContainer)),
) {
    val state = vm.state.collectAsStateWithLifecycle().value ?: return
    val feedback = LocalFeedback.current
    val restorer = remember(vm) { FocusRestorer(vm.focusMemory) }
    val listState = rememberSavedLazyListState(vm.focusMemory, PANEL)
    val toaster = LocalToaster.current
    LaunchedEffect(vm) {
        vm.signOutResults.collect { signedOut ->
            if (signedOut) toaster.show("Signed out", "Your library stays on this TV") else toaster.show("Couldn't sign out — try again")
        }
    }
    val rows = settingRows(state, vm, onSignIn)
    val defaults = state.accountDefaults
    var focusedKey by rememberSaveable { mutableStateOf(if (defaults != null) DEFAULTS_APPLY else rows.first().key) }

    // First data, and again when the defaults row appears / goes (its buttons vanish while focused).
    LaunchedEffect(defaults != null) { restorer.restore(settingsFocusRows(defaults != null, rows.map { it.key }), listState) }

    // Prototype geometry in 1280×720 design units (§6); the content starts after the 72-unit rail.
    Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 44.dp)) {
        Column(Modifier.width(580.dp)) {
            Text(
                "Settings",
                fontSize = 44.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Playback feel, content and your TMDB account",
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 44.dp),
            ) {
                if (defaults != null) {
                    item(key = DEFAULTS_ROW) {
                        AccountDefaultsRow(
                            text = defaultsLabel(defaults.language, defaults.region),
                            focusModifier = { key, index -> with(restorer) { Modifier.rememberFocus(PANEL, DEFAULTS_ROW, key, index) } },
                            onFocused = { focusedKey = it },
                            onResolve = { apply -> vm.resolveAccountDefaults(defaults, apply) },
                        )
                    }
                }
                items(rows, key = { it.key }) { row ->
                    SettingRowItem(
                        row,
                        feedback,
                        with(restorer) { Modifier.rememberFocus(PANEL, row.key, row.key, 0) }
                            .onFocusChanged { if (it.isFocused) focusedKey = row.key },
                    )
                }
            }
        }
        Spacer(Modifier.width(60.dp))
        DescriptionPane(
            row = rows.firstOrNull { it.key == focusedKey },
            defaultsText = defaults?.let { defaultsDescription(it.language, it.region, it.includeAdult) },
            // Shrinks while the expanded rail pushes the content aside.
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 440.dp).padding(top = 96.dp),
        )
    }
}

private fun defaultsLabel(language: String, region: String) =
    "Use your TMDB account defaults (${LANGUAGE_NAMES[language]}, ${REGION_NAMES[region]})"

private fun defaultsDescription(language: String, region: String, adult: Boolean) =
    "Your TMDB account prefers ${LANGUAGE_NAMES[language]} content for ${REGION_NAMES[region]}, with adult content " +
        (if (adult) "included" else "hidden") + ". Apply them here, or dismiss to keep your current settings."

@Composable
private fun SettingRowItem(row: SettingRow, feedback: Feedback, modifier: Modifier) {
    val choice = row.value as? RowValue.Choice
    Surface(
        onClick = row.onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics { contentDescription = "${row.label}, ${row.value.spoken()}" }
            .onKeyEvent { event ->
                // ←→ change a value row instead of moving focus (so LEFT never opens the rail from here).
                val delta = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onKeyEvent false
                }
                if (choice == null) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    choice.onCycle(delta)
                    feedback.focusMove(if (delta < 0) FocusDirection.Left else FocusDirection.Right)
                }
                true
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (feedback.reduceMotion) 1f else 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(row.glyph, fontSize = 18.sp)
            Text(row.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            when (val value = row.value) {
                is RowValue.Toggle -> Switch(checked = value.on, onCheckedChange = null)
                is RowValue.Choice -> Text("‹  ${value.text}  ›", fontSize = 14.sp)
                is RowValue.Label -> Text(value.text, fontSize = 14.sp)
            }
        }
    }
}

private fun RowValue.spoken() = when (this) {
    is RowValue.Toggle -> if (on) "on" else "off"
    is RowValue.Choice -> text
    is RowValue.Label -> text
}

/** §7.9: focusable, never a toast action; shown until applied or dismissed. */
@Composable
private fun AccountDefaultsRow(
    text: String,
    focusModifier: (key: String, index: Int) -> Modifier,
    onFocused: (String) -> Unit,
    onResolve: (apply: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onResolve(true) },
                modifier = focusModifier(DEFAULTS_APPLY, 0).onFocusChanged { if (it.isFocused) onFocused(DEFAULTS_APPLY) },
            ) { Text("Apply") }
            OutlinedButton(
                onClick = { onResolve(false) },
                modifier = focusModifier(DEFAULTS_DISMISS, 1).onFocusChanged { if (it.isFocused) onFocused(DEFAULTS_DISMISS) },
            ) { Text("Dismiss") }
        }
    }
}

@Composable
private fun DescriptionPane(row: SettingRow?, defaultsText: String?, modifier: Modifier) {
    Column(modifier) {
        if (row == null) {
            // The account-defaults buttons are focused.
            PaneHeading("↺", "TMDB account defaults")
            defaultsText?.let { PaneText(it) }
            return@Column
        }
        PaneHeading(row.glyph, row.label)
        if (row.key == ABOUT) AboutPane(Modifier.padding(top = 12.dp)) else PaneText(row.description)
    }
}

@Composable
private fun PaneHeading(glyph: String, label: String) {
    Box(Modifier.size(72.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
    }
    Text(
        label,
        modifier = Modifier.padding(top = 22.dp),
        fontSize = 30.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PaneText(text: String) {
    Text(text, modifier = Modifier.padding(top = 12.dp), fontSize = 16.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
