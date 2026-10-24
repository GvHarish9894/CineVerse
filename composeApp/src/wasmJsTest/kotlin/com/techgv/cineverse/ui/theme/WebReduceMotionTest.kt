package com.techgv.cineverse.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.ReduceMotionLabel
import com.techgv.cineverse.ui.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalWasmJsInterop::class)
private fun installMotionProbe(): Unit = js("(function(){ window.__cvOldMatchMedia=window.matchMedia; window.__cvMotion=false; window.__cvMotionQuery=new EventTarget(); Object.defineProperty(window.__cvMotionQuery,'matches',{get:function(){return window.__cvMotion;}}); window.matchMedia=function(q){return q==='(prefers-reduced-motion: reduce)' ? window.__cvMotionQuery : window.__cvOldMatchMedia.call(window,q);}; })()")

@OptIn(ExperimentalWasmJsInterop::class)
private fun changeMotionPreference(): Unit = js("(function(){window.__cvMotion=true;window.__cvMotionQuery.dispatchEvent(new MediaQueryListEvent('change',{matches:true,media:'(prefers-reduced-motion: reduce)'}));})()")

@OptIn(ExperimentalWasmJsInterop::class)
private fun restoreMotionProbe(): Unit = js("(function(){window.matchMedia=window.__cvOldMatchMedia;delete window.__cvMotionQuery;delete window.__cvMotion;delete window.__cvOldMatchMedia;})()")

/** Recomposes whenever its parent does (unstable parameter), so it counts recompositions of the theme content. */
private class Counter(var compositions: Int = 0)

@Composable
private fun UnrelatedContent(counter: Counter) {
    counter.compositions++
    Text("unrelated")
}

/** Ported from the L3 review probe (ReviewMotionTest), plus: only readers of LocalReduceMotion recompose. */
@OptIn(ExperimentalTestApi::class)
class WebReduceMotionTest {
    @Test
    fun osMotionPreferenceChangeUpdatesTheThemeWithoutUnrelatedRecomposition() = runComposeUiTest {
        installMotionProbe()
        try {
            val counter = Counter()
            setThemedContent {
                Column {
                    ReduceMotionLabel()
                    UnrelatedContent(counter)
                }
            }
            onNodeWithText("motion-normal").assertExists()
            val before = counter.compositions
            runOnIdle { changeMotionPreference() }
            waitForIdle()
            onNodeWithText("motion-reduced").assertExists()
            assertEquals(before, counter.compositions, "the OS preference change recomposed content that does not read it")
        } finally {
            restoreMotionProbe()
        }
    }
}
