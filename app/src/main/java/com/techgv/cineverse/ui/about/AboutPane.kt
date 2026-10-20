package com.techgv.cineverse.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.BuildConfig
import com.techgv.cineverse.R

/** TMDB terms of use: this exact notice next to the TMDB logo. */
const val TMDB_ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."

val APP_VERSION: String get() = "v${BuildConfig.VERSION_NAME}"

/** §7.9 About: TMDB logo, attribution sentence, app version. */
@Composable
fun AboutPane(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(painterResource(R.drawable.tmdb_logo), contentDescription = "TMDB", modifier = Modifier.height(20.dp))
        Text(TMDB_ATTRIBUTION, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "CineVerse $APP_VERSION (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
