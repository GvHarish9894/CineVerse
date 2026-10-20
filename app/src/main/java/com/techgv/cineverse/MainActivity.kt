package com.techgv.cineverse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.ui.feedback.ProvideFeedback
import com.techgv.cineverse.ui.nav.AppNav
import com.techgv.cineverse.ui.theme.CineVerseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CineVerseTheme {
                val settings = appContainer.settingsRepository.settings.collectAsStateWithLifecycle(Settings())
                ProvideFeedback(settings) {
                    Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                        AppNav(appContainer)
                    }
                }
            }
        }
    }
}
