@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.data.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Notifications contain only a topic and random revision marker, never account/auth or stored payloads. */
enum class BrowserChangeTopic { Settings, Library, Session, Revalidate }
/** Tab-local invalidation counters survive Flow conflation; they are not durable or session generations. */
data class BrowserRevisions(val settings: Long = 0, val library: Long = 0, val session: Long = 0, val revalidate: Long = 0)

/** Share one bus with all browser stores and the session adapter. Notifications are not an import lock. */
class BrowserChanges(channelName: String = "cineverse.changes") {
    private val mutable = MutableStateFlow(BrowserRevisions())
    val changes: StateFlow<BrowserRevisions> = mutable.asStateFlow()
    private var disposed = false
    private val bridge = listen(channelName) { topic ->
        BrowserChangeTopic.entries.firstOrNull { it.name == topic.toString() }?.let(::receive)
    }

    private fun receive(topic: BrowserChangeTopic) {
        if (disposed) return
        val old = mutable.value
        mutable.value = when (topic) {
            BrowserChangeTopic.Settings -> old.copy(settings = old.settings + 1)
            BrowserChangeTopic.Library -> old.copy(library = old.library + 1)
            BrowserChangeTopic.Session -> old.copy(session = old.session + 1)
            BrowserChangeTopic.Revalidate -> old.copy(revalidate = old.revalidate + 1)
        }
    }

    /** Call after a committed local/server change. No-op after close; notification cannot fail a committed write. */
    fun publish(topic: BrowserChangeTopic) {
        if (disposed) return
        receive(topic)
        broadcast(bridge, topic.name)
    }

    fun close() {
        if (!disposed) dispose(bridge)
        disposed = true
    }
}

@JsFun("""(name, receive) => {
    const marker = '__' + name + '.revision';
    let channel = null;
    const accept = data => {
        if (!data || !['Settings','Library','Session','Revalidate'].includes(data.topic) || typeof data.revision !== 'string') return;
        receive(data.topic);
    };
    try { channel = new BroadcastChannel(name); channel.onmessage = event => accept(event.data); } catch (_) {}
    const storage = event => {
        if (event.key !== marker || !event.newValue) return;
        try { accept(JSON.parse(event.newValue)); } catch (_) {}
    };
    const focus = () => receive('Revalidate');
    const visibility = () => { if (document.visibilityState === 'visible') focus(); };
    window.addEventListener('storage', storage);
    window.addEventListener('focus', focus);
    document.addEventListener('visibilitychange', visibility);
    return {channel, marker, storage, focus, visibility};
}""")
private external fun listen(name: String, receive: (JsString) -> Unit): JsAny

@JsFun("""(bridge, topic) => {
    const message = {topic, revision:globalThis.crypto?.randomUUID?.() || (Date.now() + ':' + Math.random())};
    let sent = false;
    try { if (bridge.channel) { bridge.channel.postMessage(message); sent = true; } } catch (_) {}
    // Marker fallback is best effort. Focus/visibility always refetch even when both transports are unavailable.
    if (!sent) { try { localStorage.setItem(bridge.marker, JSON.stringify(message)); } catch (_) {} }
}""")
private external fun broadcast(bridge: JsAny, topic: String)

@JsFun("""bridge => {
    bridge.channel?.close();
    window.removeEventListener('storage', bridge.storage);
    window.removeEventListener('focus', bridge.focus);
    document.removeEventListener('visibilitychange', bridge.visibility);
}""")
private external fun dispose(bridge: JsAny)
