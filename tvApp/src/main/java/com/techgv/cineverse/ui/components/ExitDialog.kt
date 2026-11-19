package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.techgv.cineverse.ui.feedback.FeedbackRoot

/** §7.11 Exit: app mark, question, Stay (default focus, also BACK) / Exit. */
@Composable
fun ExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val stay = remember { FocusRequester() }
    Dialog(onDismissRequest = onStay) {
        // A Dialog window is a new composition root: re-apply the design canvas and the §9 key hook.
        DesignCanvas { FeedbackRoot {
            Column(
                Modifier
                    .width(520.dp)
                    .background(DIALOG_SURFACE, DIALOG_SHAPE)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), DIALOG_SHAPE)
                    .padding(horizontal = 40.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The rail's app mark.
                Box(Modifier.size(56.dp).background(UiColors.Accent, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text("▶", color = Color.White, fontSize = 24.sp)
                }
                Spacer(Modifier.height(20.dp))
                Text("Exit CineVerse?", color = UiColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Everything will be right here when you come back.",
                    color = UiColors.TextDim,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ActionButton("Stay", "✓", onStay, Modifier.focusRequester(stay), style = ActionButtonStyle.Solid)
                    ActionButton("Exit", "✕", onExit)
                }
            }
        } }
    }
    LaunchedEffect(Unit) { stay.requestFocus() }
}

private val DIALOG_SURFACE = Color(0xFF15131F)
private val DIALOG_SHAPE = RoundedCornerShape(24.dp)
