package com.techgv.cineverse.di

import com.techgv.cineverse.data.library.LibraryDatabase
import org.koin.dsl.koinApplication
import kotlin.test.Test

class IosSharedModulesTest {
    @Test
    fun everyDefinitionResolvesWithTheIosPlatformModule() {
        val app = koinApplication { modules(sharedModules(testNativeConfig)) }
        try {
            app.koin.assertNativeGraph()
            app.koin.get<LibraryDatabase>().close()
        } finally {
            app.close()
        }
    }
}
