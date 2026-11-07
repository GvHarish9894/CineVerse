package com.techgv.cineverse.data.auth

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

internal actual fun platformLanguageTag(): String =
    NSLocale.currentLocale.let { locale -> locale.countryCode?.let { "${locale.languageCode}-$it" } ?: locale.languageCode }
