@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import com.techgv.cineverse.ui.design.Scrims
import com.techgv.cineverse.ui.web.focusWebCanvas
import kotlin.js.JsAny

actual fun cvDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, scrimColor = Scrims.DialogScrim)

private var nextModal = 0
private class WebDialogBoundary(val closeFocus: FocusRequester) {
    val id = "cv-modal-${++nextModal}"
    var dom: JsAny? = null
    var slider: JsAny? = null
}
private val LocalWebDialogBoundary = staticCompositionLocalOf<WebDialogBoundary> { error("No web dialog boundary") }

@Composable
internal actual fun cvDialogSemantics(title: String, ownedElementId: String?): Modifier {
    val boundary = LocalWebDialogBoundary.current
    SideEffect { boundary.dom?.let { updateModal(it, title, ownedElementId) } }
    return Modifier.testTag(boundary.id).semantics { dialog(); paneTitle = title }
}

@Composable
internal actual fun cvDialogWindow(
    onDismissRequest: () -> Unit, closeFocus: FocusRequester, restoreFocus: FocusRequester?,
    onRestoreFocus: (() -> Unit)?, content: @Composable () -> Unit,
) {
    // CMP 1.11.1 exposes only the last scene owner and cannot restore it after Dialog disposal.
    // Stay in the existing scene so closing a modal cannot orphan the background accessibility tree.
    val boundary = remember { WebDialogBoundary(closeFocus) }
    val restore by rememberUpdatedState(onRestoreFocus)
    val dismiss by rememberUpdatedState(onDismissRequest)
    val group = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    // Async approval/account phases can remove the focused action without removing the modal.
    LaunchedEffect(hasFocus) { if (!hasFocus) closeFocus.requestFocus() }
    DisposableEffect(boundary) {
        boundary.dom = openModal(boundary.id)
        onDispose {
            val canRestore = boundary.dom?.let(::closeModal) ?: false
            boundary.dom = null
            // A stale disposal must not move focus out of a newer modal.
            if (canRestore) {
                focus.clearFocus(force = true)
                restore?.invoke() ?: restoreFocus?.let { runCatching { it.requestFocus() } }
                focusWebCanvas()
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(Scrims.DialogScrim).pointerInput(Unit) {
            detectTapGestures { dismiss() }
        })
        CompositionLocalProvider(LocalWebDialogBoundary provides boundary) {
            Box(Modifier.focusRequester(group).onFocusChanged { hasFocus = it.hasFocus }.focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when (event.key) {
                        Key.Escape -> { dismiss(); true }
                        Key.Tab -> {
                            val direction = if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                            if (!focus.moveFocus(direction)) {
                                focus.clearFocus(force = true)
                                if (direction == FocusDirection.Next) closeFocus.requestFocus()
                                else group.requestFocus(direction)
                            }
                            focusWebCanvas()
                            true
                        }
                        else -> false
                    }
                },
            ) { content() }
        }
    }
}

/** Maps the real rating semantics node, not a second DOM slider or a second sequential Tab stop. */
@Composable
internal fun webDialogSlider(
    value: Double?, valueText: String, onChange: (Double) -> Unit, onSubmit: () -> Unit, onDismiss: () -> Unit,
): Modifier {
    val boundary = LocalWebDialogBoundary.current
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val change by rememberUpdatedState(onChange)
    val submit by rememberUpdatedState(onSubmit)
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(boundary) {
        val handle = bindSlider(checkNotNull(boundary.dom), { change(it) }, { action ->
            when (action) {
                0 -> submit()
                1 -> dismiss()
                else -> {
                    focusWebCanvas()
                    if (!focus.moveFocus(if (action == 2) FocusDirection.Next else FocusDirection.Previous)) boundary.closeFocus.requestFocus()
                }
            }
        }, { requester.requestFocus(); Unit })
        boundary.slider = handle
        onDispose { releaseSlider(handle); if (boundary.slider === handle) boundary.slider = null }
    }
    SideEffect { boundary.slider?.let { updateSlider(it, value ?: 0.0, valueText) } }
    return Modifier.focusRequester(requester)
}

private val modalRegistry = createModalRegistry()
private fun openModal(id: String): JsAny = openModal(modalRegistry, id)

/** Own only the live semantics node and its background siblings, never a duplicate DOM control tree. */
@JsFun("""() => {
    const owners = [], hidden = new Map(), sliderOwners = new WeakMap();
    let observer = null;
    const roots = new Set();
    const attrs = ['role', 'aria-label', 'aria-modal', 'aria-owns'];
    function set(n, k, v) { if (v === null) { if(n.hasAttribute(k)) n.removeAttribute(k); } else if(n.getAttribute(k) !== v) n.setAttribute(k,v); }
    function restoreHidden() {
        for (const [n, a] of hidden) { set(n,'aria-hidden',a[0]); set(n,'inert',a[1]); }
        hidden.clear();
    }
    function visit(root, id) {
        if (!roots.has(root)) { roots.add(root); observer.observe(root,{childList:true,subtree:true,attributes:true,attributeFilter:['role','id','aria-label','aria-modal','aria-owns']}); }
        for (const n of root.querySelectorAll('*')) {
            if (n.id === id) return n;
            if (n.shadowRoot) { const found = visit(n.shadowRoot,id); if(found) return found; }
        }
        return null;
    }
    function releaseSliderNode(s) {
        const n=s.node;
        if(!n) return;
        n.removeEventListener('keydown',s.key); n.removeEventListener('focus',s.focus);
        if(sliderOwners.get(n)===s) { s.attrs.forEach((k,i)=>set(n,k,s.saved[i])); sliderOwners.delete(n); }
        s.node=null;
    }
    function syncSlider(owner,node) {
        const s=owner.slider;
        if(!s) return;
        const n=node.querySelector('#web-rate-stars');
        if(n!==s.node) {
            releaseSliderNode(s);
            if(n) {
                const previous=sliderOwners.get(n); if(previous) releaseSliderNode(previous);
                s.node=n; s.saved=s.attrs.map(k=>n.getAttribute(k)); sliderOwners.set(n,s);
                n.addEventListener('keydown',s.key); n.addEventListener('focus',s.focus);
            }
        }
        if(!n) return;
        // Zero is the existing semantics range's unchosen state, never a submitted TMDB score.
        set(n,'role','slider'); set(n,'aria-valuemin','0'); set(n,'aria-valuemax','10');
        set(n,'aria-valuenow',String(s.value)); set(n,'aria-valuetext',s.text); set(n,'tabindex','-1');
    }
    function sync() {
        restoreHidden();
        const owner = owners[owners.length-1];
        if (!owner) return;
        const node = visit(document.getElementById('cineverse-root') || document.body,owner.id);
        if (!node) return;
        if (!owner.nodes.has(node)) owner.nodes.set(node,attrs.map(k=>node.getAttribute(k)));
        set(node,'role','dialog'); set(node,'aria-modal','true'); set(node,'aria-label',owner.title);
        set(node,'aria-owns',owner.owned);
        syncSlider(owner,node);
        let child = node;
        while (child.parentElement && child.parentElement.id !== 'cmp_a11y_root') {
            for (const sibling of child.parentElement.children) if(sibling !== child) {
                hidden.set(sibling,[sibling.getAttribute('aria-hidden'),sibling.getAttribute('inert')]);
                set(sibling,'aria-hidden','true'); set(sibling,'inert','');
            }
            child = child.parentElement;
        }
        if (child.parentElement) for(const sibling of child.parentElement.children) if(sibling !== child) {
            hidden.set(sibling,[sibling.getAttribute('aria-hidden'),sibling.getAttribute('inert')]);
            set(sibling,'aria-hidden','true'); set(sibling,'inert','');
        }
    }
    return {
        open(id) {
            const owner = {id,title:'',owned:null,nodes:new Map(),live:true};
            owners.push(owner);
            if (!observer) observer = new MutationObserver(sync);
            sync();
            return {
                update(title,owned) { if(owner.live) { owner.title=title; owner.owned=owned; sync(); } },
                slider(adjust,action,focus) {
                    if(owner.slider) releaseSliderNode(owner.slider);
                    const s={node:null,value:0,text:'Not chosen',live:true,focus,
                        attrs:['role','aria-valuemin','aria-valuemax','aria-valuenow','aria-valuetext','tabindex']};
                    s.key=e=>{
                        if(!s.live || !owner.live || owner.slider!==s) return;
                        let next=null, command=null;
                        switch(e.key) {
                            case 'ArrowRight': case 'ArrowUp': next=Math.min(10,s.value+0.5); break;
                            case 'ArrowLeft': case 'ArrowDown': next=Math.max(0.5,s.value-0.5); break;
                            case 'Home': next=0.5; break; case 'End': next=10; break;
                            case 'Enter': command=0; break; case 'Escape': command=1; break;
                            case 'Tab': command=e.shiftKey?3:2; break;
                            default: return;
                        }
                        e.preventDefault(); e.stopPropagation();
                        if(next!==null) { s.value=next; adjust(next); }
                        else action(command);
                    };
                    owner.slider=s; sync();
                    return {
                        update(value,text) { if(s.live && owner.live && owner.slider===s) { s.value=value; s.text=text; sync(); } },
                        close() { s.live=false; releaseSliderNode(s); if(owner.slider===s) owner.slider=null; }
                    };
                },
                close() {
                    if(!owner.live) return false;
                    owner.live=false;
                    if(owner.slider) { owner.slider.live=false; releaseSliderNode(owner.slider); owner.slider=null; }
                    owners.splice(owners.indexOf(owner),1);
                    for(const [n,a] of owner.nodes) attrs.forEach((k,i)=>set(n,k,a[i]));
                    restoreHidden();
                    if(owners.length) sync();
                    else { observer.disconnect(); observer=null; roots.clear(); }
                    return owners.length===0;
                }
            };
        }
    };
}""")
private external fun createModalRegistry(): JsAny
@JsFun("(registry,id) => registry.open(id)")
private external fun openModal(registry: JsAny, id: String): JsAny
@JsFun("(handle,title,owned) => handle.update(title,owned)")
private external fun updateModal(handle: JsAny, title: String, owned: String?)
@JsFun("(handle) => handle.close()")
private external fun closeModal(handle: JsAny): Boolean

@JsFun("(modal,adjust,action,focus) => modal.slider(adjust,action,focus)")
private external fun bindSlider(modal: JsAny, adjust: (Double) -> Unit, action: (Int) -> Unit, focus: () -> Unit): JsAny
@JsFun("(handle,value,text) => handle.update(value,text)")
private external fun updateSlider(handle: JsAny, value: Double, text: String)
@JsFun("(handle) => handle.close()")
private external fun releaseSlider(handle: JsAny)
