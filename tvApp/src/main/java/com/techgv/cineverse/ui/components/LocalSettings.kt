package com.techgv.cineverse.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.techgv.cineverse.data.settings.Settings

/**
 * The current §7.9 settings for UI that reacts to them (`motion` = Reduce motion, `glow` = Ambient glow).
 * Provided once by `MainActivity` from `SettingsRepository.settings`.
 */
val LocalSettings = compositionLocalOf { Settings() }
