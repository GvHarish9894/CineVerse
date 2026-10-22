package com.techgv.cineverse.data.auth

/** This platform's [TokenCipher] for tests; [loseKey] simulates a wiped / restored key. */
interface LosableTokenCipher : TokenCipher {
    fun loseKey()
}

expect fun testTokenCipher(): LosableTokenCipher
