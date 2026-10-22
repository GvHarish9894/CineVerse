package com.techgv.cineverse.data.tmdb

import kotlin.jvm.JvmInline

/** v4 account id (string) from `/4/auth/access_token`; path id of `/4/account/{id}/...`. */
@JvmInline
value class AccountObjectId(val value: String)

/** v3 numeric account id from `/3/account`; path id of v3 writes. */
@JvmInline
value class AccountId(val value: Int)

@JvmInline
value class SessionId(val value: String) {
    override fun toString() = "SessionId(<redacted>)"
}

@JvmInline
value class UserAccessToken(val value: String) {
    override fun toString() = "UserAccessToken(<redacted>)"
}

@JvmInline
value class ReadToken(val value: String) {
    override fun toString() = "ReadToken(<redacted>)"
}
