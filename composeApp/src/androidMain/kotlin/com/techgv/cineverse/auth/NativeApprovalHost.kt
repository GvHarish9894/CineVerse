package com.techgv.cineverse.auth

import android.app.Activity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** Activity adapter: no UI/navigation policy and no strong Activity retained by the application graph. */
class NativeApprovalHost {
    private val flow = KoinPlatform.getKoin().get<CustomTabsApprovalFlow>()
    private val opener = KoinPlatform.getKoin().get<AndroidCustomTabsOpener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun attach(activity: Activity) { opener.attach(activity) }
    fun onCallback(uri: String) {
        scope.launch {
            try {
                flow.onCallback(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Coordinator exposes Failed; do not crash the host or log callback/token data.
            }
        }
    }
    fun stop() { scope.launch { flow.coordinator.cancel() } }
    fun detach(activity: Activity) {
        opener.detach(activity)
        scope.cancel()
    }
}
