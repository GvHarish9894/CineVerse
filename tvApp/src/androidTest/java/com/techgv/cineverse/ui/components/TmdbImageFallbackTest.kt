package com.techgv.cineverse.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** §1.5: the text / initials fallback shows without a URL and when the URL fails; a loaded image hides it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TmdbImageFallbackTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(url: String?) = compose.setContent {
        TmdbImage(url, seed = "seed", modifier = Modifier.size(140.dp, 210.dp)) { Text(FALLBACK) }
    }

    @Test fun noUrlShowsTheFallback() {
        show(null)
        compose.waitUntilAtLeastOneExists(hasText(FALLBACK), 5_000)
    }

    @Test fun failedUrlShowsTheFallback() {
        show("file:///data/local/tmp/cineverse-missing-${System.nanoTime()}.png")
        compose.waitUntilAtLeastOneExists(hasText(FALLBACK), 5_000)
    }

    @Test fun loadedUrlHidesTheFallback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "tmdb-image-test.png")
        file.outputStream().use { Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        show(file.toURI().toString())
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(FALLBACK)).fetchSemanticsNodes().isEmpty() }
        // Still hidden once the image settled (no late error flips it back).
        compose.waitForIdle()
        compose.waitUntilDoesNotExist(hasText(FALLBACK), 1_000)
    }

    private companion object {
        const val FALLBACK = "Fallback title"
    }
}
