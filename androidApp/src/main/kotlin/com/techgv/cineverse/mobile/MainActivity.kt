package com.techgv.cineverse.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.techgv.cineverse.smoke.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(nativeReadToken = BuildConfig.TMDB_READ_ACCESS_TOKEN.ifBlank { null }) }
    }
}
