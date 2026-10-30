package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.Composable

// Pushed routes use NavHost's native edge-swipe; tab roots have no iOS system Back action.
@Composable
internal actual fun MobileRootBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
