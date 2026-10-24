package com.techgv.cineverse.auth

import com.techgv.cineverse.data.auth.NativeApprovalCoordinator
import com.techgv.cineverse.data.auth.NativeApprovalOpener
import com.techgv.cineverse.data.auth.RequestCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebAuthenticationSessionApprovalFlowTest {
    @Test fun replacingWindowCannotBeDetachedByTheOldHost() {
        val opener = IosWebAuthenticationSessionOpener()
        val old = platform.UIKit.UIWindow()
        val current = platform.UIKit.UIWindow()
        opener.attach(old)
        opener.attach(current)
        kotlin.test.assertFalse(opener.detach(old))
        kotlin.test.assertTrue(opener.detach(current))
        kotlin.test.assertFalse(opener.detach(current))
    }

    @Test fun injectedPlatformBoundaryPersistsOpensAndVerifies() = runTest {
        var opens = 0
        approvalFlowBoundaryFixture { auth, pending ->
            val opener = object : NativeApprovalOpener {
                override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) { opens++ }
                override fun cancel() = Unit
            }
            WebAuthenticationSessionApprovalFlow(NativeApprovalCoordinator(auth, pending, opener, { RequestCode("fake") }, backgroundScope))
        }
        assertEquals(1, opens)
    }
}
