package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.testTokenCipher
import com.techgv.cineverse.data.library.LibraryDatabase
import com.techgv.cineverse.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.error.InstanceCreationException
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmSharedModulesTest {
    private val directory = Files.createTempDirectory("cineverse-koin").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun everyDefinitionResolvesWithTheJvmPlatformModule() {
        val app = koinApplication {
            modules(sharedModules(testNativeConfig) + module { single { JvmStorage(directory, testTokenCipher()) } })
        }
        try {
            app.koin.assertNativeGraph()
            app.koin.get<LibraryDatabase>().close()
        } finally {
            app.close()
        }
    }

    @Test
    fun twoGraphsShareNoSingletonsAndClosingOneKeepsTheOther() {
        fun graph() = koinApplication {
            modules(sharedModules(testNativeConfig) + module { single { JvmStorage(directory, testTokenCipher()) } })
        }
        val first = graph()
        val second = graph()
        val scope = first.koin.get<CoroutineScope>(AppScope)
        assertNotSame(scope, second.koin.get<CoroutineScope>(AppScope))
        assertNotSame(first.koin.get<SettingsRepository>(), second.koin.get<SettingsRepository>())
        second.close()
        assertSame(scope, first.koin.get<CoroutineScope>(AppScope))
        first.close()
    }

    @Test
    fun aGraphWithoutJvmStorageFailsWhenKoinStarts() {
        val error = assertFailsWith<InstanceCreationException> { koinApplication { modules(sharedModules(testNativeConfig)) } }
        val cause = assertIs<NoDefinitionFoundException>(error.cause)
        assertTrue("JvmStorage" in cause.message.orEmpty(), cause.message)
    }
}
