package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.domain.Video
import com.techgv.cineverse.ui.components.ButtonVariant
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle

/**
 * SCREENS §10 Trailer: the YouTube player is an owned HTML iframe over the dialog's 16:9 slot (see [WebTrailerHost]);
 * the iframe owns all playback controls. Never shows for an empty list or an invalid/unlisted [initial]. Closing
 * removes the iframe before [onClose]; leaving composition removes it before focus returns to [restoreFocus].
 */
@Composable
fun WebTrailerDialog(
    videos: List<Video>,
    initial: Video,
    autoplay: Boolean,
    onClose: () -> Unit,
    restoreFocus: FocusRequester,
    host: WebTrailerHost,
) {
    val owner = remember(videos, initial, autoplay, host) { WebTrailerOwner(videos, initial, autoplay, host) }
    val current = owner.current ?: return
    val density = LocalDensity.current.density
    // Kit `.player` is 1000 wide; short windows shrink it so the 16:9 slot, caption and More videos strip still fit (never below 560).
    val windowHeight = (LocalWindowInfo.current.containerSize.height / density).dp
    val playerWidth = ((windowHeight - 320.dp) * (16f / 9f) + 72.dp).coerceIn(560.dp, 1000.dp)
    val close = { owner.close(); onClose() }
    CvDialog(
        onDismissRequest = close,
        title = current.name,
        kicker = current.type,
        restoreFocus = restoreFocus,
        modifier = Modifier.testTag("web-trailer-dialog"),
        maxWidth = playerWidth,
    ) {
        Box(
            Modifier.padding(top = 20.dp).fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp)).background(Color.Black)
                .onGloballyPositioned { val px = it.boundsInWindow(); owner.place(Rect(px.topLeft / density, px.bottomRight / density)) }
                .testTag("web-trailer-player-slot"),
        )
        Text("Plays on YouTube. Use the player's own controls.", Modifier.padding(top = 10.dp),
            style = bodyStyle(13.sp), color = Tokens.Colors.TextSecondary)
        val more = owner.videos - current
        if (more.isNotEmpty()) {
            Text("More videos", Modifier.padding(top = 18.dp, bottom = 8.dp), style = bodyStyle(15.sp, FontWeight.SemiBold), color = Tokens.Colors.TextPrimary)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("web-trailer-more-videos"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                more.forEach { video -> PillButton("${video.name} · ${video.type}", { owner.select(video) }, variant = ButtonVariant.Ghost, small = true) }
            }
        }
    }
    // Registered after CvDialog so it is disposed first: the iframe goes before CvDialog restores focus.
    DisposableEffect(owner) { onDispose { owner.close() } }
}
