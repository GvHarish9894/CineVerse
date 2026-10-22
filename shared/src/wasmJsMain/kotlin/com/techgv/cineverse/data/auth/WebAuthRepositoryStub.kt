package com.techgv.cineverse.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Web: always a guest. Sign-in throws; must not ship — replaced by the /api/auth proxy client in P9. */
class WebAuthRepositoryStub : AuthRepository {
    override val session: Flow<AuthSession?> = flowOf(null)
    override val sessionExpired: Flow<Unit> = emptyFlow()

    override suspend fun createRequestCode(): RequestCode = throw unsupported()
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome = throw unsupported()

    /** A guest is already signed out locally. */
    override suspend fun signOut(onSignedOutLocally: () -> Unit) = onSignedOutLocally()
    override suspend fun expireSession(failed: AuthSession) = Unit

    private fun unsupported() = UnsupportedOperationException("Web sign-in is implemented in P9 via /api/auth")
}
