package com.techgv.cineverse.auth

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.techgv.cineverse.data.auth.ApprovalFlow
import com.techgv.cineverse.data.auth.NativeApprovalCoordinator
import com.techgv.cineverse.data.auth.NativeApprovalOpener
import com.techgv.cineverse.data.auth.NativeAuthRepository
import com.techgv.cineverse.data.auth.PendingAttemptStore
import java.lang.ref.WeakReference

/** Phone flow; the application owns the coordinator, the current Activity only supplies presentation. */
class CustomTabsApprovalFlow(val coordinator: NativeApprovalCoordinator) : ApprovalFlow by coordinator {
    constructor(auth: NativeAuthRepository, pending: PendingAttemptStore, opener: NativeApprovalOpener) :
        this(NativeApprovalCoordinator(auth, pending, opener, auth::createRequestCode))
}

class AndroidCustomTabsOpener : NativeApprovalOpener {
    private var activity = WeakReference<Activity>(null)

    fun attach(activity: Activity) { this.activity = WeakReference(activity) }
    fun detach(activity: Activity) {
        if (this.activity.get() === activity) this.activity.clear()
    }

    override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) {
        val host = checkNotNull(activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }) {
            "Approval requires a resumed Activity"
        }
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(host, Uri.parse(url))
    }

    // Custom Tabs are browser-owned: Back returns to the app; checkAgain() is the explicit manual-return action.
    override fun cancel() = Unit
}
