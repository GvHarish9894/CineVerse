@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.testTag
import kotlin.js.JsAny
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

internal enum class WebModalKind { Trailer, Rate, Account, Approval }

/** A return target is captured by the opening action, not by a later async dialog emission. */
internal class WebModalFocus(private val fallback: FocusRequester) {
    private val triggers = mutableMapOf<WebModalKind, WebModalTrigger>()
    fun capture(kind: WebModalKind, trigger: WebModalTrigger) { triggers[kind] = trigger }
    fun restorer(kind: WebModalKind): () -> Unit {
        val trigger = triggers[kind]
        return {
            if (trigger?.live != true) runCatching { fallback.requestFocus() }
            else if (!runCatching { trigger.requester.requestFocus() }.getOrDefault(false)) {
                runCatching { fallback.requestFocus() }
            } else afterWebFocusCommit {
                if (!trigger.live || !trigger.focused) runCatching { fallback.requestFocus() }
            }
            Unit
        }
    }
}

internal val LocalWebModalFocus = staticCompositionLocalOf<WebModalFocus?> { null }

internal class WebModalTrigger(private val owner: WebModalFocus?, private val kind: WebModalKind) {
    val requester = FocusRequester()
    var live = false
    var focused = false
        private set
    val modifier: Modifier get() = Modifier.focusRequester(requester).onFocusChanged { focused = it.isFocused }
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
internal fun webHeaderFocusBridge(home: FocusRequester?, modalOpen: Boolean, searchFocused: Boolean): Modifier {
    val group = remember { FocusRequester() }
    val blocked by rememberUpdatedState(modalOpen)
    var bridge by remember { mutableStateOf<JsAny?>(null) }
    DisposableEffect(home, group) {
        val installed = installHeaderFocusBridge { backwards ->
            if (!blocked) {
                // shortcut: 2-D entry picks the right-most stop = last in LTR; revisit if RTL ships.
                if (backwards) group.requestFocus(FocusDirection.Left) else home?.requestFocus()
            }
        }
        bridge = installed
        onDispose { disposeHeaderFocusBridge(installed) }
    }
    // AT lands on Compose's unnamed IME input while the field is focused, so it borrows the field's name.
    DisposableEffect(bridge, searchFocused) {
        val current = bridge
        current?.let { nameHeaderSearchInput(it, searchFocused) }
        onDispose { current?.let { nameHeaderSearchInput(it, false) } }
    }
    return Modifier.testTag("web-header").focusRequester(group).focusGroup()
}

@JsFun("""(enter) => {
    let canvas=null, backwards=false, entering=false;
    let named=false;
    const observed=new Set(), editors=new Map(), labelled=new Set();
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
    function nameInput() {
        for(const input of labelled) if(!named || !input.isConnected) { input.removeAttribute('aria-label'); labelled.delete(input); }
        const input=named && canvas && canvas.parentElement && canvas.parentElement.querySelector(':scope > input, :scope > textarea');
        // accname: aria-labelledby -> a textbox contributes only its (empty) value, so copy the label text instead.
        const root=input && input.getRootNode();
        const mirror=input && (root && root.getElementById ? root : document).getElementById('header-search-input');
        const label=mirror && mirror.getAttribute('aria-label');
        if(input && label && !labelled.has(input)) { input.setAttribute('aria-label',label); labelled.add(input); }
    }
    const observer=new MutationObserver(()=>{ scan(document.getElementById('cineverse-root') || document.body); nameInput(); });
    document.addEventListener('keydown',onKey,true);
    scan(document.getElementById('cineverse-root') || document.body);
    return {name(on) { named=on; nameInput(); }, close() {
        named=false; nameInput();
        observer.disconnect(); document.removeEventListener('keydown',onKey,true); if(canvas) canvas.removeEventListener('focus',onFocus);
        for(const [node,tabindex] of editors) { if(tabindex===null) node.removeAttribute('tabindex'); else node.setAttribute('tabindex',tabindex); }
    }};
}""")
private external fun installHeaderFocusBridge(enter: (Boolean) -> Unit): JsAny
@JsFun("(bridge) => bridge.close()")
private external fun disposeHeaderFocusBridge(bridge: JsAny)
@JsFun("(bridge, on) => bridge.name(on)")
private external fun nameHeaderSearchInput(bridge: JsAny, on: Boolean)

@JsFun("(verify) => { queueMicrotask(verify); }")
private external fun afterWebFocusCommit(verify: () -> Unit)

/** Backing-input disposal can blur after Compose moves focus; repair once after that event, never poll. */
internal fun focusWebCanvasAfterInput() {
    focusWebCanvas()
    afterInputBlur { focusWebCanvas() }
}
@JsFun("(restore) => { queueMicrotask(restore); }")
private external fun afterInputBlur(restore: () -> Unit)
