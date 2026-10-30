package com.techgv.cineverse.ui.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.koin.core.scope.Scope

internal actual fun Scope.mobileExternalActions(): MobileExternalActions = AndroidMobileExternalActions(get())

/** Application context, so every hand-off starts its own task instead of retaining an Activity. */
internal class AndroidMobileExternalActions(private val context: Context) : MobileExternalActions {
    override suspend fun openYouTube(key: String): Boolean = isYouTubeKey(key) &&
        (start(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key"))) || openUrl(youTubeWatchUrl(key)))

    override suspend fun openUrl(url: String): Boolean =
        url.startsWith("https://") && start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    override fun share(text: String): Boolean = start(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null,
    ))

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: RuntimeException) {
        false // No handler (ActivityNotFoundException) or a restricted target: report, never crash the root.
    }
}
