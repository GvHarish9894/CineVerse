package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.settings.InMemorySettingsStore
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.AccountDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §7.9 (moved from tvApp SettingsModelTest / SettingsViewModelTest; auth faked instead of NativeAuthRepository). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateHolderTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()
    private val settings = SettingsRepository(InMemorySettingsStore())
    private val holder by lazy { SettingsStateHolder(settings, auth) }
    private val ann = session("a", "Ann", 1)
    private val bob = session("b", "Bob", 2)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun cycleWrapsBothWays() {
        assertEquals(HapticStrength.Strong, HapticStrength.entries.cycle(HapticStrength.Medium, 1))
        assertEquals(HapticStrength.Light, HapticStrength.entries.cycle(HapticStrength.Strong, 1))
        assertEquals("es-ES", Settings.LANGUAGES.cycle("en-US", -1))
        assertEquals("IN", Settings.REGIONS.cycle("GB", 1))
    }

    @Test fun everySupportedValueHasADisplayName() {
        assertEquals(Settings.LANGUAGES.toSet(), LANGUAGE_NAMES.keys)
        assertEquals(Settings.REGIONS.toSet(), REGION_NAMES.keys)
    }

    @Test fun accountDefaultsMapIsoCodesOntoSupportedValues() {
        val offer = accountDefaultsOffer(session(), AccountDto(1, language = "hi", region = "US", includeAdult = true), Settings())
        assertEquals(AccountDefaults("aoid", "hi-IN", "US", true), offer)
    }

    @Test fun unsupportedLanguageOrRegionKeepsTheCurrentValue() {
        val offer = accountDefaultsOffer(session(), AccountDto(1, language = "fr", region = "FR"), Settings(includeAdult = true))
        assertEquals(AccountDefaults("aoid", "en-US", "IN", false), offer)
    }

    @Test fun noOfferWhenTheAccountDefaultsMatchTheCurrentSettings() {
        assertNull(accountDefaultsOffer(session(), AccountDto(1, language = "en", region = "IN"), Settings()))
        assertNull(accountDefaultsOffer(session(), AccountDto(1, language = null, region = null), Settings()))
    }

    @Test fun togglesAndChoicesEditTheSettings() = runTest(dispatcher) {
        holder.toggle(Toggle.Adult)
        holder.cycle(Choice.Language, 1)
        holder.cycle(Choice.Region, -1)
        runCurrent()
        assertEquals(Settings(includeAdult = true, language = "hi-IN", region = "GB"), settings.current())
    }

    @Test fun anUnresolvedOfferReturnsWithItsAccountAndAStaleClickNeverResolvesAnotherAccountsOffer() = runTest(dispatcher) {
        backgroundScope.launch { holder.state.collect {} }
        val a = AccountDefaults("a", "hi-IN", "US", includeAdult = false)
        val b = AccountDefaults("b", "es-ES", "GB", includeAdult = false)
        auth.sessions.value = ann
        settings.offerAccountDefaults(a)
        runCurrent()
        assertEquals(a, holder.state.value?.accountDefaults)
        assertEquals("Ann", holder.state.value?.accountName)

        auth.sessions.value = bob
        settings.offerAccountDefaults(b)
        runCurrent()
        assertEquals(b, holder.state.value?.accountDefaults)

        holder.resolveAccountDefaults(a, apply = true) // stale click from A's row while B is signed in
        runCurrent()
        assertEquals(b, holder.state.value?.accountDefaults)
        assertEquals(Settings(), settings.current())

        auth.sessions.value = ann
        runCurrent()
        assertEquals(a, holder.state.value?.accountDefaults)
    }

    @Test fun signedOutIsReportedOnlyAfterTheLocalClearOnceWhileTheRemoteRevocationStillRuns() = runTest(dispatcher) {
        auth.sessions.value = ann
        val results = mutableListOf<Boolean>()
        backgroundScope.launch { holder.signOutResults.collect { results += it } }
        val localClear = CompletableDeferred<Unit>().also { auth.localClear = it }
        auth.remoteRevocation = CompletableDeferred()

        holder.signOut()
        holder.signOut() // duplicate press while in flight
        runCurrent()
        assertEquals(emptyList(), results)

        localClear.complete(Unit)
        runCurrent()
        assertEquals(listOf(true), results)
        assertEquals(1, auth.signOutCalls)
    }

    @Test fun aFailedLocalClearReportsFailureAndAllowsAnotherTry() = runTest(dispatcher) {
        auth.sessions.value = ann
        val results = mutableListOf<Boolean>()
        backgroundScope.launch { holder.signOutResults.collect { results += it } }
        auth.signOutError = IOException("disk full")

        holder.signOut()
        runCurrent()
        assertEquals(listOf(false), results)

        auth.signOutError = null
        holder.signOut()
        runCurrent()
        assertEquals(listOf(false, true), results)
        assertNull(auth.sessions.value)
    }
}
