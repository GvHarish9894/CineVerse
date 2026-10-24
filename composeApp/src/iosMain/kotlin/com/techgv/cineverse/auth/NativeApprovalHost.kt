package com.techgv.cineverse.auth

import com.techgv.cineverse.data.auth.NativeApprovalCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import platform.UIKit.UIWindow

private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

fun attachNativeApprovalWindow(window: UIWindow) {
    KoinPlatform.getKoin().get<IosWebAuthenticationSessionOpener>().attach(window)
}

fun detachNativeApprovalWindow(window: UIWindow) {
    if (KoinPlatform.getKoin().get<IosWebAuthenticationSessionOpener>().detach(window)) {
        callbackScope.launch { KoinPlatform.getKoin().get<NativeApprovalCoordinator>().cancel() }
    }
}

/** Only a background scene stops verification; system authentication presentation may make it inactive. */
fun handleNativeApprovalScenePhase(isBackground: Boolean) {
    callbackScope.launch {
        handleNativeApprovalScenePhase(isBackground, KoinPlatform.getKoin().get<NativeApprovalCoordinator>())
    }
}

internal suspend fun handleNativeApprovalScenePhase(isBackground: Boolean, coordinator: NativeApprovalCoordinator) {
    if (isBackground) coordinator.cancel()
}

/** SwiftUI openURL covers an external/cold-start return; the retained session also feeds this verifier. */
fun handleNativeApprovalUrl(uri: String) {
    callbackScope.launch {
        try {
            KoinPlatform.getKoin().get<NativeApprovalCoordinator>().onCallback(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Coordinator exposes Failed; platform callbacks must not crash the host or log callback/token data.
        }
    }
}
