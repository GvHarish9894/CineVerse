package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

val PERSON_SIZE = 132.dp

/** Up to two initials of [name] (prototype `initials`): "Cillian Murphy" → "CM". */
fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }

/** 132 dp circle with profile (or initials) + name + [role] (§6). */
@Composable
fun PersonCard(
    name: String,
    role: String,
    profilePath: String?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val url = tmdbImageUrl(profilePath, ImageSize.PROFILE)
    val glow = if (focused) rememberPaletteColor(url, fallbackColor(seed)) else fallbackColor(seed)
    Column(modifier.width(PERSON_SIZE)) {
        FocusSurface(
            onClick = onClick,
            shape = CircleShape,
            glowColor = glow,
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = listOf(name, role).filter { it.isNotEmpty() }.joinToString(", ") }
                .size(PERSON_SIZE),
        ) {
            TmdbImage(url, seed, Modifier.fillMaxSize()) {
                Text(
                    initials(name),
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        CardLabel(name, role, focused, centered = true)
    }
}
