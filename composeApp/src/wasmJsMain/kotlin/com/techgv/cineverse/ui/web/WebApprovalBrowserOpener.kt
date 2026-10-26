@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.web.WebApprovalAttempt

/** Supply explicitly at the root. No async work and no interpretation of window.open's nullable result. */
object WebApprovalBrowserOpener : WebApprovalOpener {
    override fun open(approval: WebApprovalAttempt) {
        openApprovalTab(approval.approveUrl)
    }
}

@JsFun("(url) => { window.open(url, '_blank', 'noopener,noreferrer'); }")
private external fun openApprovalTab(url: String)
