package com.techgv.cineverse.data.tmdb

import platform.Foundation.NSLocale
import platform.Foundation.localizedStringForLanguageCode

internal actual fun englishLanguageName(code: String): String? =
    NSLocale(localeIdentifier = "en").localizedStringForLanguageCode(code)
