package com.techgv.cineverse.auth

import com.techgv.cineverse.data.auth.NativeApprovalCoordinator
import com.techgv.cineverse.data.auth.NativeApprovalOpener
import com.techgv.cineverse.data.auth.RequestCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomTabsApprovalFlowTest {
    @Test fun openingWithoutAnActivityFailsVisibly() {
        kotlin.test.assertFailsWith<IllegalStateException> {
            AndroidCustomTabsOpener().open("https://example.invalid", {}, {})
        }
    }

    @Test fun injectedPlatformBoundaryPersistsOpensAndVerifies() = runTest {
        var opens = 0
        approvalFlowBoundaryFixture { auth, pending ->
            val opener = object : NativeApprovalOpener {
                override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) { opens++ }
                override fun cancel() = Unit
            }
            CustomTabsApprovalFlow(NativeApprovalCoordinator(auth, pending, opener, { RequestCode("fake") }, backgroundScope))
        }
        assertEquals(1, opens)
    }
}
