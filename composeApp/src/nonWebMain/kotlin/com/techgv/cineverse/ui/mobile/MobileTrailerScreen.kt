package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.components.*
import kotlinx.coroutines.launch

/** Open-out only: playback belongs to YouTube; an invalid route key never reaches the platform. */
@Composable
internal fun MobileTrailerScreen(route: MobileTrailerRoute, external: MobileExternalActions, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var failure by remember(route) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 110.dp)
        .testTag("mobile-trailer"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PillButton("Back", onBack, variant = ButtonVariant.Ghost)
        SectionTitle(route.title)
        Text(route.kicker)
        if (!isYouTubeKey(route.key)) Text("Trailer unavailable. This video link is not valid.")
        else {
            PillButton("Open in YouTube", {
                failure = null
                scope.launch { if (!external.openYouTube(route.key)) failure = "Couldn't open YouTube." }
            })
            PillButton("Share", {
                failure = if (external.share(youTubeWatchUrl(route.key))) null else "Couldn't open sharing."
            }, variant = ButtonVariant.Ghost)
            failure?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }
}
