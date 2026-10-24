@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.techgv.cineverse.auth

import com.techgv.cineverse.data.auth.ApprovalFlow
import com.techgv.cineverse.data.auth.NativeApprovalCoordinator
import com.techgv.cineverse.data.auth.NativeApprovalOpener
import com.techgv.cineverse.data.auth.NativeAuthRepository
import com.techgv.cineverse.data.auth.PendingAttemptStore
import kotlin.native.ref.WeakReference
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIWindow
import platform.darwin.NSObject

class WebAuthenticationSessionApprovalFlow(val coordinator: NativeApprovalCoordinator) : ApprovalFlow by coordinator {
    constructor(auth: NativeAuthRepository, pending: PendingAttemptStore, opener: NativeApprovalOpener) :
        this(NativeApprovalCoordinator(auth, pending, opener, auth::createRequestCode))
}

/** Retains the system session and its provider, not the host window. Host detach cancels before disposal. */
class IosWebAuthenticationSessionOpener : NativeApprovalOpener {
    private var window: WeakReference<UIWindow>? = null
    private var session: ASWebAuthenticationSession? = null
    private var provider: AnchorProvider? = null
    private var generation = 0L

    // ObjC weak loads can produce another Kotlin wrapper for the same native window: use NSObject equality.
    fun attach(window: UIWindow) {
        if (this.window?.get() != window) cancel()
        this.window = WeakReference(window)
    }
    fun detach(window: UIWindow): Boolean {
        if (this.window?.get() != window) return false
        cancel()
        this.window = null
        return true
    }

    override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) {
        cancel()
        val anchor = checkNotNull(window?.get()) { "Approval requires a visible window" }
        val current = generation
        provider = AnchorProvider(anchor)
        val next = ASWebAuthenticationSession(
            uRL = checkNotNull(NSURL.URLWithString(url)),
            callbackURLScheme = "cineverse",
            completionHandler = { callback, _ ->
                if (current == generation) {
                    session = null
                    provider = null
                    callback?.absoluteString?.let(onCallback) ?: onCancel()
                }
            },
        )
        next.presentationContextProvider = provider
        session = next // retain before start(), including a synchronous platform completion
        if (!next.start()) {
            cancel()
            error("Unable to start approval session")
        }
    }

    override fun cancel() {
        generation++ // programmatic cancellation must not cancel a newer coordinator operation
        val previous = session
        session = null
        provider = null
        previous?.cancel()
    }

    private class AnchorProvider(window: UIWindow) : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        private val window = WeakReference(window)
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow =
            checkNotNull(window.get()) { "Approval window was detached" }
    }
}
