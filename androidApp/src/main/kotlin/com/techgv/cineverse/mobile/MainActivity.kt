package com.techgv.cineverse.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.techgv.cineverse.ui.CineVerseApp
import com.techgv.cineverse.ui.adaptive.ShellStyle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: the glass top bar and floating tab bar draw under the system bars and pad by their insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { CineVerseApp(ShellStyle.Mobile, nativeReadToken = BuildConfig.TMDB_READ_ACCESS_TOKEN.ifBlank { null }) }
    }
}
