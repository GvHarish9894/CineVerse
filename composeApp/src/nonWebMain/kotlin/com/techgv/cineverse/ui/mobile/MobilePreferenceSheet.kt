package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.presentation.REGION_NAMES
import com.techgv.cineverse.ui.components.*

/** Exact supported codes cross this boundary; labels are presentation only. */
@Composable
internal fun MobilePreferenceSheet(
    picker: MobilePreferencePicker,
    settings: Settings,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = picker == MobilePreferencePicker.Language
    val values = if (language) Settings.LANGUAGES else Settings.REGIONS
    val selected = if (language) settings.language else settings.region
    CvBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("profile-picker"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OverlayHeading("CONTENT", if (language) "Content language" else "Region")
            Text(if (language) "Titles and overviews" else "Release dates and certifications")
            values.forEach { value ->
                Chip(if (language) profileLanguageLabel(value) else REGION_NAMES.getValue(value) + " · " + value,
                    selected == value, { onSelect(value) }, Modifier.fillMaxWidth().testTag("profile-option-$value"))
            }
            PillButton("Cancel", onDismiss, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
        }
    }
}
