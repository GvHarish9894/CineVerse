@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.auth.IosWebAuthenticationSessionOpener
import kotlin.coroutines.resume
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.scope.Scope
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIPopoverPresentationController
import platform.UIKit.popoverPresentationController

internal actual fun Scope.mobileExternalActions(): MobileExternalActions = IosMobileExternalActions(get())

/** Presents from the approval opener's weakly attached host window; holds no window of its own. */
internal class IosMobileExternalActions(private val host: IosWebAuthenticationSessionOpener) : MobileExternalActions {
    override suspend fun openYouTube(key: String): Boolean = isYouTubeKey(key) &&
        (open("youtube://www.youtube.com/watch?v=$key") || openUrl(youTubeWatchUrl(key)))

    override suspend fun openUrl(url: String): Boolean = url.startsWith("https://") && open(url)

    private suspend fun open(url: String): Boolean {
        val target = NSURL.URLWithString(url) ?: return false
        return suspendCancellableCoroutine { continuation ->
            UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any?>(), completionHandler = { opened ->
                if (continuation.isActive) continuation.resume(opened)
            })
        }
    }

    override fun share(text: String): Boolean {
        val window = host.attachedWindow ?: return false
        val presenter = generateSequence(window.rootViewController ?: return false) { it.presentedViewController }.last()
        val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        // iPad shows the sheet as a popover, which requires an anchor.
        sheet.popoverPresentationController?.let { popover ->
            popover.sourceView = window
            popover.sourceRect = window.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
        }
        presenter.presentViewController(sheet, animated = true, completion = null)
        return true
    }
}
