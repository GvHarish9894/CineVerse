@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.web.WebApprovalAttempt

/** Supply explicitly at the root. No async work and no interpretation of window.open's nullable result. */
object WebApprovalBrowserOpener : WebApprovalOpener {
    override fun openPending(): WebApprovalOpener.PendingTab {
        val tab = openPlaceholderTab()
        return object : WebApprovalOpener.PendingTab {
            override fun navigate(url: String) = navigateTab(tab, url)
            override fun close() = closeTab(tab)
        }
    }

    override fun open(approval: WebApprovalAttempt) {
        openApprovalTab(approval.approveUrl)
    }
}

@JsFun("(url) => { window.open(url, '_blank', 'noopener,noreferrer'); }")
private external fun openApprovalTab(url: String)

// A blocked popup is null: navigate/close are then no-ops and the dialog keeps its "Open TMDB again" path.
@JsFun("""() => {
    let tab = null;
    try { tab = window.open('about:blank', '_blank'); } catch (_) {}
    if (!tab) return null;
    try { tab.opener = null; } catch (_) {}
    try { tab.document.title = 'CineVerse'; tab.document.body.textContent = 'Redirecting you to TMDB…'; } catch (_) {}
    return tab;
}""")
private external fun openPlaceholderTab(): JsAny?

// The anchor lives in the placeholder document, so rel=noreferrer governs this navigation (not our page's policy).
@JsFun("""(tab, url) => {
    if (!tab || tab.closed) return;
    try {
        const link = tab.document.createElement('a');
        link.href = url; link.rel = 'noreferrer noopener';
        tab.document.body.appendChild(link);
        link.click();
    } catch (_) { try { tab.location.replace(url); } catch (_) {} }
}""")
private external fun navigateTab(tab: JsAny?, url: String)

@JsFun("(tab) => { try { if (tab && !tab.closed) tab.close(); } catch (_) {} }")
private external fun closeTab(tab: JsAny?)
