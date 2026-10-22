package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.data.tmdb.yearOf
import com.techgv.cineverse.domain.PersonCredit

/** §6 CreditCard 308×110 (step 324): poster, year or "—", title, role sub-line. [modifier] goes on the focusable. */
@Composable
fun CreditCard(credit: PersonCredit, posterUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    DetailCardSurface(onClick = onClick, modifier = modifier.size(308.dp, 110.dp)) {
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DetailCardArt(posterUrl, credit.title, Modifier.fillMaxHeight().width(60.dp).clip(RoundedCornerShape(8.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(yearOf(credit.date) ?: "—", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    credit.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (credit.role.isNotEmpty()) {
                    Text(
                        credit.role,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
