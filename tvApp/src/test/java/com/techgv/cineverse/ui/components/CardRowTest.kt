package com.techgv.cineverse.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CardRowTest {
    @Test fun focusGrowthIsHalfTheExtraHeightOnEachSide() {
        assertEquals(8.4f, focusGrowth(POSTER_HEIGHT, 1.08f).value, 0.001f)
    }

    @Test fun rowHeightIsPaddingCardAndLabels() {
        assertEquals(290.dp, cardRowHeightOf(POSTER_HEIGHT, labels = 54.dp, top = 14.dp, bottom = 12.dp))
    }

    @Test fun paddingTooSmallForTheFocusedCardIsRejected() {
        // A 210 dp poster grows 8.4 dp per side at 1.08: 8 dp of padding would let it overlap its neighbours.
        assertThrows(IllegalArgumentException::class.java) { cardRowHeightOf(POSTER_HEIGHT, 0.dp, top = 8.dp, bottom = 12.dp) }
    }
}
