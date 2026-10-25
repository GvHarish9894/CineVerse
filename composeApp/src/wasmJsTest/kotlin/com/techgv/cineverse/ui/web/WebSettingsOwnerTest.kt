package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebSettingsOwnerTest {
    @Test fun observesActualValuesAndEverySupportedDirectChoice() = runTest {
        val f = WebSettingsFixture(backgroundScope)
        try {
            assertNull(f.owner.state.value.settings)
            runCurrent()
            assertEquals("ta-IN", f.owner.state.value.settings?.language)
            assertEquals("GB", f.owner.state.value.settings?.region)
            assertEquals(Settings.LANGUAGES.toSet(), LANGUAGE_NAMES.keys)
            assertEquals(Settings.REGIONS.toSet(), REGION_NAMES.keys)
            Settings.LANGUAGES.reversed().forEach { language ->
                f.owner.selectLanguage(language); runCurrent()
                assertEquals(language, f.owner.state.value.settings?.language)
            }
            Settings.REGIONS.reversed().forEach { region ->
                f.owner.selectRegion(region); runCurrent()
                assertEquals(region, f.owner.state.value.settings?.region)
            }
            HapticStrength.entries.forEach { strength ->
                f.owner.selectStrength(strength); runCurrent()
                assertEquals(strength, f.owner.state.value.settings?.strength)
            }
            assertEquals(f.owner.state.value.settings, f.holder.state.value?.settings)
            assertEquals(f.owner.state.value.settings, f.owner.state.value.holderSettings)
            assertFailsWith<IllegalArgumentException> { f.owner.selectLanguage("fr-FR") }
            assertFailsWith<IllegalArgumentException> { f.owner.selectRegion("FR") }
        } finally { f.close() }
    }

    @Test fun atomicDirectEditsPreserveLatestUnrelatedPreferencesRecentsAndAccountKeys() = runTest {
        val f = WebSettingsFixture(backgroundScope)
        try {
            runCurrent()
            val retained = f.store.snapshot().filterKeys { it.startsWith("account_") || it == "search.recents" || it == "unrelated" }
            f.store.writeGate = CompletableDeferred()
            f.owner.selectLanguage("hi-IN"); runCurrent()
            f.store.externalChange { it.setString("region", "US"); it.setBoolean("sounds", false) }
            f.owner.setToggle(Toggle.Glow, true)
            f.owner.setToggle(Toggle.Motion, false)
            f.owner.setToggle(Toggle.Haptics, true)
            f.owner.setToggle(Toggle.Adult, false)
            f.store.writeGate!!.complete(Unit); runCurrent()
            val observed = f.repository.current()
            assertEquals("hi-IN", observed.language)
            assertEquals("US", observed.region)
            assertFalse(observed.sounds)
            assertTrue(observed.glow)
            assertFalse(observed.motion)
            assertTrue(observed.haptics)
            assertFalse(observed.includeAdult)
            assertEquals(retained, f.store.snapshot().filterKeys { it in retained })
            assertEquals(0, f.owner.state.value.pendingWrites)
        } finally { f.close() }
    }

    @Test fun unknownAndTypedCorruptReadNeverBecomeDefaultsOrWrite() = runTest {
        val f = WebSettingsFixture(backgroundScope)
        try {
            f.store.readFailure = BrowserStorageFailure.Corrupt
            f.owner.selectLanguage("es-ES")
            runCurrent()
            assertNull(f.owner.state.value.settings)
            assertNotNull(f.owner.state.value.readFailure)
            assertEquals(0, f.store.writes)
            val before = f.store.snapshot()
            f.owner.setToggle(Toggle.Adult, false); runCurrent()
            assertEquals(before, f.store.snapshot())
            f.store.readFailure = null
            runCurrent()
            assertNull(f.owner.state.value.settings)
            f.owner.retryStorage(); runCurrent()
            assertEquals(1, f.upstreamRetries)
            assertEquals("ta-IN", f.owner.state.value.settings?.language)
            assertNull(f.owner.state.value.readFailure)
            assertEquals(0, f.store.writes)
        } finally { f.close() }
    }

    @Test fun failedReadersRecoverEqualValuesInSameOwnerAndHolderExplicitly() = runTest {
        val f = WebSettingsFixture(backgroundScope)
        try {
            runCurrent()
            val before = f.owner.state.value.settings
            f.store.readFailure = BrowserStorageFailure.Blocked
            f.store.signalRead(); runCurrent()
            assertNotNull(f.owner.state.value.readFailure)
            assertTrue(f.failures.isNotEmpty())
            assertEquals(0, f.store.activeReaders)
            val subscriptions = f.store.subscriptions
            f.store.readFailure = null
            f.owner.retryStorage(); runCurrent()
            assertTrue(f.store.subscriptions >= subscriptions + 3)
            assertEquals(1, f.upstreamRetries)
            assertEquals(before, f.owner.state.value.settings)
            assertEquals(before, f.holder.state.value?.settings)
            assertNull(f.owner.state.value.readFailure)
            assertEquals(0, f.store.writes)
        } finally { f.close() }
    }

    @Test fun failedWriteIsSeparateStickyAndReadRecoveryNeverReplaysIt() = runTest {
        val f = WebSettingsFixture(backgroundScope)
        try {
            runCurrent()
            f.store.writeFailure = BrowserStorageFailure.Quota
            f.owner.selectLanguage("es-ES"); runCurrent()
            assertNotNull(f.owner.state.value.writeFailure)
            assertNull(f.owner.state.value.readFailure)
            assertEquals("ta-IN", f.owner.state.value.settings?.language)
            f.store.writeFailure = null
            f.owner.retryStorage(); runCurrent()
            assertEquals(1, f.store.writes)
            assertNotNull(f.owner.state.value.writeFailure)
            f.owner.selectLanguage("es-ES"); runCurrent()
            assertEquals(2, f.store.writes)
            assertEquals("es-ES", f.owner.state.value.settings?.language)
            f.owner.dismissWriteFailure()
            assertNull(f.owner.state.value.writeFailure)
        } finally { f.close() }
    }

    @Test fun externalAndEqualEmissionsStayObservedWithoutMutations() = runTest {
        val f = WebSettingsFixture(backgroundScope)
        try {
            runCurrent()
            f.store.externalChange { it.setString("language", "ml-IN"); it.setBoolean("glow", true) }; runCurrent()
            assertEquals("ml-IN", f.owner.state.value.settings?.language)
            assertTrue(f.owner.state.value.settings!!.glow)
            val state = f.owner.state.value
            f.store.signalRead(); runCurrent()
            assertEquals(state, f.owner.state.value)
            assertEquals(0, f.store.writes)
        } finally { f.close() }
    }

    @Test fun disposalCancelsPendingWritesAndReadersAndReentryReadsFreshValues() = runTest {
        val f = WebSettingsFixture(backgroundScope)
        try {
            runCurrent()
            f.store.writeGate = CompletableDeferred()
            f.owner.selectRegion("IN"); runCurrent()
            f.owner.close(); runCurrent()
            assertEquals(1, f.store.cancelledWrites)
            val stopped = f.owner.state.value
            val retries = f.upstreamRetries
            f.owner.retryStorage(); f.owner.selectLanguage("es-ES"); f.owner.signIn()
            f.store.externalChange { it.setString("language", "es-ES") }
            f.store.writeGate!!.complete(Unit); runCurrent()
            assertEquals(stopped, f.owner.state.value)
            assertEquals(retries, f.upstreamRetries)
            assertEquals(1, f.store.writes)
            assertEquals(0, f.signIns)
            assertEquals("GB", f.repository.current().region)
            val reentered = f.owner()
            try {
                runCurrent()
                assertEquals("es-ES", reentered.state.value.settings?.language)
                assertEquals("GB", reentered.state.value.settings?.region)
                reentered.close(); runCurrent()
                // Holder's five-second sharing timeout belongs to the app; local readers stop immediately.
                testScheduler.advanceTimeBy(5_001); runCurrent()
                assertEquals(0, f.store.activeReaders)
            } finally { reentered.close() }
        } finally { f.close() }
    }

    @Test fun authoritativeSessionGatesCallbacksAndCapturesCurrentRealIdentity() = runTest {
        val f = WebSettingsFixture(backgroundScope)
        try {
            runCurrent()
            f.owner.signIn(); f.owner.signOut(); f.owner.importLibrary()
            assertEquals(0, f.signIns)
            f.session.value = WebSessionState.Failed(TmdbError.Network)
            f.owner.signIn(); f.owner.signOut(); f.owner.importLibrary()
            assertTrue(f.signOuts.isEmpty()); assertTrue(f.imports.isEmpty())
            f.session.value = WebSessionState.Ready(null)
            f.owner.signIn(); f.owner.signOut(); f.owner.importLibrary()
            assertEquals(1, f.signIns)
            val account = AuthSession(AccountObjectId("actual-account"), "Actual name", 876L)
            f.session.value = WebSessionState.Ready(account)
            f.owner.signIn(); f.owner.signOut(); f.owner.importLibrary()
            val newer = account.copy(sessionGeneration = 877L)
            f.session.value = WebSessionState.Ready(newer)
            f.owner.signOut()
            assertEquals(1, f.signIns)
            assertEquals(listOf(account, newer), f.signOuts)
            assertEquals(listOf(account), f.imports)
            f.session.value = WebSessionState.Checking
            f.owner.signOut(); f.owner.importLibrary()
            assertEquals(2, f.signOuts.size)
            assertEquals(1, f.imports.size)
        } finally { f.close() }
    }
}
