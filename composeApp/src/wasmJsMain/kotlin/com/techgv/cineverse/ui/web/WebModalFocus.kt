@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.testTag
import kotlin.js.JsAny
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

internal enum class WebModalKind { Trailer, Rate, Account, Approval }

/** A return target is captured by the opening action, not by a later async dialog emission. */
internal class WebModalFocus(private val fallback: FocusRequester) {
    private val triggers = mutableMapOf<WebModalKind, WebModalTrigger>()
    fun capture(kind: WebModalKind, trigger: WebModalTrigger) { triggers[kind] = trigger }
    fun restorer(kind: WebModalKind): () -> Unit {
        val trigger = triggers[kind]
        return {
            if (trigger?.live == true) runCatching { trigger.requester.requestFocus() }
            else runCatching { fallback.requestFocus() }
            Unit
        }
    }
}

internal val LocalWebModalFocus = staticCompositionLocalOf<WebModalFocus?> { null }

internal class WebModalTrigger(private val owner: WebModalFocus?, private val kind: WebModalKind) {
    val requester = FocusRequester()
    var live = false
    val modifier: Modifier get() = Modifier.focusRequester(requester)
    fun open(action: () -> Unit) {
        owner?.capture(kind, this)
        action()
    }
}

@Composable
internal fun rememberWebModalTrigger(kind: WebModalKind): WebModalTrigger {
    val owner = LocalWebModalFocus.current
    val trigger = remember(owner, kind) { WebModalTrigger(owner, kind) }
    DisposableEffect(trigger) {
        trigger.live = true
        onDispose { trigger.live = false }
    }
    return trigger
}

/** The real canvas must own DOM focus whenever a Compose action is selected (not BODY after input blur). */
@JsFun("""() => {
    function find(root) {
        const canvas=root.querySelector('canvas'); if(canvas) return canvas;
        for(const n of root.querySelectorAll('*')) if(n.shadowRoot) { const found=find(n.shadowRoot); if(found) return found; }
        return null;
    }
    const canvas=find(document.getElementById('cineverse-root') || document.body);
    if(canvas) {
        canvas.setAttribute('data-cv-focus-return','');
        canvas.focus({preventScroll:true});
        canvas.removeAttribute('data-cv-focus-return');
    }
}""")
internal external fun focusWebCanvas()

@Composable
internal fun webHeaderFocusBridge(home: FocusRequester?, modalOpen: Boolean): Modifier {
    val group = remember { FocusRequester() }
    val blocked by rememberUpdatedState(modalOpen)
    DisposableEffect(home, group) {
        val bridge = installHeaderFocusBridge { backwards ->
            if (!blocked) {
                if (backwards) group.requestFocus(FocusDirection.Previous) else home?.requestFocus()
            }
        }
        onDispose { disposeHeaderFocusBridge(bridge) }
    }
    return Modifier.testTag("web-header").focusRequester(group).focusGroup()
}

@JsFun("""(enter) => {
    let canvas=null, backwards=false, entering=false;
    const observed=new Set(), editors=new Map();
    const onKey=e=>{
        if(e.key!=='Tab') return;
        backwards=e.shiftKey;
        const path=e.composedPath(), root=canvas && canvas.getRootNode();
        entering=!!canvas && !path.includes(canvas) && !path.some(n=>n.nodeType===1 && n.getRootNode()===root);
    };
    const onFocus=()=>{
        const route=entering && !canvas.hasAttribute('data-cv-focus-return');
        entering=false;
        if(route) enter(backwards);
    };
    function scan(root) {
        if(!observed.has(root)) { observed.add(root); observer.observe(root,{childList:true,subtree:true}); }
        const editor=root.querySelector('#header-search-input[contenteditable]');
        if(editor && !editors.has(editor)) { editors.set(editor,editor.getAttribute('tabindex')); editor.setAttribute('tabindex','-1'); }
        const next=root.querySelector('canvas');
        if(next && next!==canvas) { if(canvas) canvas.removeEventListener('focus',onFocus); canvas=next; canvas.addEventListener('focus',onFocus); }
        for(const n of root.querySelectorAll('*')) if(n.shadowRoot) scan(n.shadowRoot);
    }
    const observer=new MutationObserver(()=>scan(document.getElementById('cineverse-root') || document.body));
    document.addEventListener('keydown',onKey,true);
    scan(document.getElementById('cineverse-root') || document.body);
    return {close() {
        observer.disconnect(); document.removeEventListener('keydown',onKey,true); if(canvas) canvas.removeEventListener('focus',onFocus);
        for(const [node,tabindex] of editors) { if(tabindex===null) node.removeAttribute('tabindex'); else node.setAttribute('tabindex',tabindex); }
    }};
}""")
private external fun installHeaderFocusBridge(enter: (Boolean) -> Unit): JsAny
@JsFun("(bridge) => bridge.close()")
private external fun disposeHeaderFocusBridge(bridge: JsAny)

/** Backing-input disposal can blur after Compose moves focus; repair once after that event, never poll. */
internal fun focusWebCanvasAfterInput() {
    focusWebCanvas()
    afterInputBlur { focusWebCanvas() }
}
@JsFun("(restore) => { queueMicrotask(restore); }")
private external fun afterInputBlur(restore: () -> Unit)
