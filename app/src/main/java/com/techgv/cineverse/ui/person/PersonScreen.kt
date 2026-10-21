package com.techgv.cineverse.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.tmdb.formatDate
import com.techgv.cineverse.domain.PersonDetail
import com.techgv.cineverse.ui.components.CreditCard
import com.techgv.cineverse.ui.components.DetailCardArt
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.ActionButton
import com.techgv.cineverse.ui.components.ActionButtonStyle
import com.techgv.cineverse.ui.components.ActionIcon
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.Section
import com.techgv.cineverse.ui.components.ScrollableText
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.screenFocusRoot
import com.techgv.cineverse.ui.components.rememberSavedLazyListState
import com.techgv.cineverse.ui.components.tmdbImageUrl
import com.techgv.cineverse.ui.detail.DetailChip
import com.techgv.cineverse.ui.detail.DetailRow
import com.techgv.cineverse.ui.detail.ROW_TOP_INSET
import com.techgv.cineverse.ui.detail.RevealVertically
import com.techgv.cineverse.ui.detail.SCREEN_INSET
import com.techgv.cineverse.ui.detail.revealHeroOnFocus
import com.techgv.cineverse.ui.feedback.LocalFeedback
import com.techgv.cineverse.ui.nav.Route

private const val PANEL = "main"
private const val BUTTONS = "buttons"
private const val KNOWN_FOR = "knownFor"
private const val CREDITS = "credits"

@Composable
fun PersonScreen(route: Route.Person, container: AppContainer, onNavigate: (Route) -> Unit) {
    val vm: PersonViewModel = viewModel {
        PersonViewModel(route, createSavedStateHandle(), container.tmdbRepository, container.settingsRepository.settings, container.appGeneration)
    }
    val person by vm.person.collectAsStateWithLifecycle()
    val bioExpanded by vm.bioExpanded.collectAsStateWithLifecycle()
    PersonContent(person, bioExpanded, vm.focusMemory, vm.imageBase, vm::toggleBio, vm::retry, onNavigate)
}

@Composable
internal fun PersonContent(
    person: SectionState<PersonDetail>,
    bioExpanded: Boolean,
    memory: FocusMemory,
    imageBase: String,
    onToggleBio: () -> Unit,
    onRetry: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    Box(Modifier.screenFocusRoot().background(MaterialTheme.colorScheme.background)) {
        when (person) {
            is SectionState.Content -> PersonBody(person.value, bioExpanded, memory, imageBase, onToggleBio, onNavigate)
            else -> Section(person, onRetry = onRetry, modifier = Modifier.padding(SCREEN_INSET)) {}
        }
    }
}

/** "Born {date}" (+ " – {deathday}"); falls back to the raw value when unparsable. */
internal fun bornChip(birthday: String?, deathday: String?): String? {
    val born = birthday?.let { formatDate(it) ?: it } ?: return null
    val died = deathday?.let { formatDate(it) ?: it }
    return "Born $born" + (died?.let { " – $it" } ?: "")
}

@Composable
private fun PersonBody(
    person: PersonDetail,
    bioExpanded: Boolean,
    memory: FocusMemory,
    imageBase: String,
    onToggleBio: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val restorer = remember(memory) { FocusRestorer(memory) }
    val outer = rememberSavedLazyListState(memory, "outer")
    val knownForState = rememberSavedLazyListState(memory, KNOWN_FOR)
    val creditsState = rememberSavedLazyListState(memory, CREDITS)
    val hasBio = person.biography.isNotBlank()
    val knownForKeys = person.knownFor.map { it.ref.key }
    val creditKeys = person.credits.map { it.ref.key }
    // Outer list item i is focus row i; the button row may be empty (no biography) but keeps its slot.
    val focusRows = listOf(
        FocusRow(PANEL, BUTTONS, if (hasBio) listOf("bio") else emptyList()),
        FocusRow(PANEL, KNOWN_FOR, knownForKeys, knownForState),
        FocusRow(PANEL, CREDITS, creditKeys, creditsState),
    )
    val scope = rememberCoroutineScope()

    RevealVertically(ROW_TOP_INSET) {
        LazyColumn(state = outer, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = BUTTONS) {
                Row(
                    Modifier.fillMaxWidth().padding(start = SCREEN_INSET, end = SCREEN_INSET, top = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    DetailCardArt(
                        tmdbImageUrl(imageBase, "h632", person.profilePath), person.name,
                        Modifier.size(240.dp, 360.dp).clip(RoundedCornerShape(16.dp)), initials = true,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        person.department?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            person.name,
                            fontSize = if (person.name.length > 17) 46.sp else 60.sp,
                            lineHeight = if (person.name.length > 17) 50.sp else 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                        )
                        val chips = listOfNotNull(bornChip(person.birthday, person.deathday), person.placeOfBirth, "${person.creditCount} credits")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips.forEach { DetailChip(it) } }
                        if (hasBio) {
                            if (person.biographyInEnglish) {
                                Text("(English)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (bioExpanded) {
                                // UP from "Show less" enters the text; DOWN pages through it, then returns to the button.
                                ScrollableText(
                                    person.biography, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onSurface,
                                    Modifier.heightIn(max = 240.dp),
                                )
                            } else {
                                Text(
                                    person.biography,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(with(restorer) { Modifier.restoreRowFocus(focusRows[0], scope) }.revealHeroOnFocus(outer, scope, LocalFeedback.current.reduceMotion)) {
                                ActionButton(
                                    if (bioExpanded) "Show less" else "Read full biography", ActionIcon.INFO, onToggleBio,
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, BUTTONS, "bio", 0) },
                                    style = ActionButtonStyle.Solid,
                                )
                            }
                        } else {
                            Text("No biography available", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item(key = KNOWN_FOR) {
                if (person.knownFor.isNotEmpty()) {
                    DetailRow("Known for", restorer, focusRows[1], knownForState) {
                        itemsIndexed(person.knownFor, key = { index, _ -> knownForKeys[index] }) { index, title ->
                            PosterCard(
                                title,
                                onClick = { onNavigate(Route.Detail(title.ref.mediaType, title.ref.id)) },
                                modifier = with(restorer) { Modifier.rememberFocus(PANEL, KNOWN_FOR, knownForKeys[index], index) },
                            )
                        }
                    }
                }
            }
            item(key = CREDITS) {
                if (person.credits.isNotEmpty()) {
                    DetailRow(person.creditsTitle, restorer, focusRows[2], creditsState) {
                        itemsIndexed(person.credits, key = { index, _ -> creditKeys[index] }) { index, credit ->
                            CreditCard(
                                credit, tmdbImageUrl(imageBase, "w342", credit.posterPath),
                                onClick = { onNavigate(Route.Detail(credit.ref.mediaType, credit.ref.id)) },
                                modifier = with(restorer) { Modifier.rememberFocus(PANEL, CREDITS, creditKeys[index], index) },
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(restorer) { restorer.restore(focusRows, outer) }
}
