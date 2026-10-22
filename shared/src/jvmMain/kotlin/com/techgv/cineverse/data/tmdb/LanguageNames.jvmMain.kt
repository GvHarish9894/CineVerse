package com.techgv.cineverse.data.tmdb

import java.util.Locale

internal actual fun englishLanguageName(code: String): String? = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
