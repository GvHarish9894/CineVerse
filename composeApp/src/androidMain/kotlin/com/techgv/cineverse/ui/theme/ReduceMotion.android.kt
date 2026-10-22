package com.techgv.cineverse.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** "Remove animations" (accessibility) / animator duration scale 0 (developer options). */
private fun ContentResolver.animatorsOff(): Boolean =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
actual fun rememberSystemReduceMotion(): State<Boolean> {
    val resolver = LocalContext.current.contentResolver
    val reduce = remember(resolver) { mutableStateOf(resolver.animatorsOff()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce.value = resolver.animatorsOff()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    // Belt and braces: a change made while the activity was stopped is picked up on return.
    LifecycleResumeEffect(resolver) {
        reduce.value = resolver.animatorsOff()
        onPauseOrDispose { }
    }
    return reduce
}
