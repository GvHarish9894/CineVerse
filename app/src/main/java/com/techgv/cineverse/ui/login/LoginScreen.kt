package com.techgv.cineverse.ui.login

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.techgv.cineverse.appContainer
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.ui.components.QrCode
import com.techgv.cineverse.ui.feedback.LocalFeedback

/**
 * §4.1 Login (prototype layout): steps + buttons + spinner on the left, QR card on the right.
 * [message] is e.g. "Sign-in expired" (§3.3). BACK is AppNav's (§1.2 step 2).
 */
@Composable
fun LoginScreen(
    message: String?,
    onContinueAsGuest: () -> Unit,
    onSignedIn: (displayName: String) -> Unit,
    vm: LoginViewModel = viewModel(factory = LoginViewModel.factory(LocalContext.current.appContainer)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val signedIn by rememberUpdatedState(onSignedIn)
    LaunchedEffect(vm) { vm.signedIn.collect { signedIn(it) } }

    // §4.1 polling stops on background / dispose (a BACK pop disposes) and when the Exit dialog
    // (BACK on a cold-start Login) takes window focus.
    LifecycleStartEffect(vm) { onStopOrDispose { vm.stopPolling() } }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) vm.stopPolling() }

    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) { primary.requestFocus() }

    Row(Modifier.fillMaxSize().padding(start = 90.dp, top = 48.dp, end = 64.dp, bottom = 32.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CineVerse", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            (state.banner ?: message)?.let { Banner(it) }
            Text(
                "Sign in with your TMDB account",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Keep your watchlist, favourites and ratings in sync between this TV, your phone and themoviedb.org.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Step(1, "Scan the code with your phone to open TMDB.")
            Step(2, "Sign in to TMDB and approve CineVerse.")
            Step(3, "Come back here and press OK on \u201cI've approved it\u201d — we will finish signing you in.")
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Stays enabled (and focused) while waiting; presses are ignored then (LoginAction.None).
                Button(onClick = vm::onPrimary, modifier = Modifier.focusRequester(primary)) { Text(state.primaryLabel) }
                OutlinedButton(onClick = { vm.stopPolling(); onContinueAsGuest() }) { Text("Continue as guest") }
            }
            Row(
                Modifier.height(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.waiting) Spinner()
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.width(56.dp))
        QrPanel(state.code, Modifier.padding(top = 40.dp))
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Rotating arc; static under Reduce motion. */
@Composable
private fun Spinner() {
    val angle = if (LocalFeedback.current.reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "spinner")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "angle").value
    }
    val track = Color.White.copy(alpha = 0.2f)
    Canvas(Modifier.size(18.dp).rotate(angle)) {
        val stroke = Stroke(width = 2.dp.toPx())
        drawArc(track, 0f, 360f, useCenter = false, style = stroke)
        drawArc(Color.White, -90f, 90f, useCenter = false, style = stroke)
    }
}

/** White QR card plus an elided link: the ~400-character URL can't be typed, so the QR is the way in. */
@Composable
private fun QrPanel(code: RequestCode?, modifier: Modifier) {
    Column(modifier.width(260.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(240.dp).background(Color.White, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            if (code != null) {
                QrCode(code.approvalUrl, contentDescription = "QR code to approve CineVerse on TMDB", size = 216.dp)
            } else {
                Text("No code yet", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
            }
        }
        if (code != null) {
            val token = code.requestToken
            Text(
                "themoviedb.org/auth/access…${token.takeLast(4)}",
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("The code expires after 15 minutes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
