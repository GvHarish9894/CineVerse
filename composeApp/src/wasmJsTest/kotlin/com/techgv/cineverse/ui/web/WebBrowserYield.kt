@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.ui.test.ComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal suspend fun ComposeUiTest.yieldToWebBrowser(condition: () -> Boolean = { true }) {
    withContext(Dispatchers.Default) {
        withTimeout(1_000) {
            do {
                delay(10)
                mainClock.advanceTimeByFrame()
                waitForIdle()
            } while (!condition())
        }
    }
}
