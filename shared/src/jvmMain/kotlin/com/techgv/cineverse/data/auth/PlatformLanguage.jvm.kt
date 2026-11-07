package com.techgv.cineverse.data.auth

import java.util.Locale

internal actual fun platformLanguageTag(): String =
    Locale.getDefault().let { if (it.country.isEmpty()) it.language else "${it.language}-${it.country}" }
