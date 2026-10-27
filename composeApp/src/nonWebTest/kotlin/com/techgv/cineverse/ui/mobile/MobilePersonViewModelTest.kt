package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.SectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** PREPARED ONLY: source-first lane has no Gradle or native runtime lease. */
@OptIn(ExperimentalCoroutinesApi::class)
class MobilePersonViewModelTest {
    private fun MobilePersonViewModel.person() = (state.value.section as? SectionState.Content)?.value

    @Test fun invalidIdsAndWrongTypedScalarsNeverSubscribeFetchOrRepairAndBackWorks() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope)
        runCurrent()
        val cases = listOf(0 to emptyMap(), -1 to emptyMap(), 42 to mapOf("bioExpanded" to "true"),
            42 to mapOf("person.index" to "2"), 42 to mapOf("person.offset" to -1), 42 to mapOf("bioExpanded" to 1))
        cases.forEach { (id, raw) ->
            val saved = SavedStateHandle(raw)
            val m = f.model(id, saved)
            m.setActive(true)
            runCurrent()
            assertTrue(m.state.value.invalid)
            var backs = 0
            m.back(m.state.value.context) { backs++ }
            assertEquals(1, backs)
            raw.forEach { (key, value) -> assertEquals(value, saved.get<Any>(key)) }
            m.close()
        }
        assertEquals(0, f.subscriptions)
        assertTrue(f.api.calls.isEmpty())
    }

    @Test fun startsInactiveAndDefaultReaderIsActualGraphRawFlow() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope)
        runCurrent()
        val before = f.store.subscriptions
        val m = MobilePersonViewModel(f.graph, 42, SavedStateHandle(), parentScope = backgroundScope)
        runCurrent()
        assertEquals(before, f.store.subscriptions)
        assertTrue(f.api.calls.isEmpty())
        m.setActive(true); runCurrent()
        assertEquals(before + 1, f.store.subscriptions)
        assertEquals(42, m.person()?.id)
        m.setActive(false); runCurrent()
        assertEquals(1, f.store.activeSubscriptions)
    }

    @Test fun rawFailureHidesContentAndEqualRecoveryResubscribesExactlyOnce() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val m = f.model(); m.setActive(true); runCurrent()
        val old = m.state.value.context
        f.failRaw(); runCurrent()
        assertTrue(m.state.value.settingsFailed)
        assertNull(m.person())
        assertEquals(0, f.readers)
        f.raw.value = Result.success(Settings())
        m.retry(m.state.value.context); runCurrent()
        assertEquals(2, f.subscriptions)
        assertEquals(1, f.readers)
        assertEquals(42, m.person()?.id)
        assertNotEquals(old, m.state.value.context)
    }

    @Test fun rawUnknownNeverUsesGraphDefaultsOrFetches() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val m = MobilePersonViewModel(f.graph, 42, SavedStateHandle(), emptyFlow(), backgroundScope)
        m.setActive(true); runCurrent()
        assertNull(m.person()); assertTrue(f.api.calls.isEmpty())
        assertFalse(m.state.value.settingsFailed)
    }

    @Test fun graphSettingsFailureAndRecoveryUseRealNewSubscription() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val m = f.model(); m.setActive(true); runCurrent()
        f.store.fail = true; f.store.values.value = mapOf("language" to "fr-FR"); runCurrent()
        assertTrue(m.state.value.settingsFailed); assertNull(m.person()); assertEquals(0, f.readers)
        f.store.fail = false; f.store.values.value = emptyMap()
        m.retry(m.state.value.context); runCurrent()
        assertFalse(m.state.value.settingsFailed); assertEquals(42, m.person()?.id)
        assertEquals(1, f.readers); assertEquals(2, f.subscriptions)
    }

    @Test fun settingsEmissionReplacesActualChildAndDoesNotTagOldData() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val m = f.model(); m.setActive(true); runCurrent()
        f.api.gate = CompletableDeferred()
        f.raw.value = Result.success(Settings(language = "fr-FR")); runCurrent()
        assertNull(m.person())
        f.api.gate!!.complete(Unit); runCurrent()
        assertEquals("Person fr-FR", m.person()?.name)
        assertEquals(listOf("42/en-US", "42/fr-FR"), f.api.calls)
    }

    @Test fun stubbornOldPrimaryCompletionCannotReplaceNewContextOrSave() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val gate = CompletableDeferred<Unit>(); f.api.gate = gate; f.api.stubborn = true
        val saved = SavedStateHandle(); val m = f.model(saved = saved)
        m.setActive(true); runCurrent()
        val old = m.state.value.context
        f.api.gate = null
        f.raw.value = Result.success(Settings(language = "fr-FR")); runCurrent()
        assertEquals("Person fr-FR", m.person()?.name)
        gate.complete(Unit); runCurrent()
        m.toggleBiography(old); m.saveScroll(old, 12, 13)
        assertEquals("Person fr-FR", m.person()?.name)
        assertNull(saved.get<Any>("bioExpanded")); assertNull(saved.get<Any>("person.index"))
    }

    @Test fun generationInvalidatesCallbacksBeforeObserverAndContextEventResubscribes() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val saved = SavedStateHandle(); val m = f.model(saved = saved); m.setActive(true); runCurrent()
        val old = m.state.value.context
        f.generation.bump()
        var opened = 0
        m.open(old, MediaRef(MediaType.Tv, 701), MobileNavigator { opened++ })
        m.toggleBiography(old); m.saveScroll(old, 4, 5)
        assertEquals(0, opened); assertNull(saved.get<Any>("bioExpanded"))
        assertNull((m.visibleState(m.state.value).section as? SectionState.Content)?.value)
        m.contextChanged(); runCurrent()
        assertEquals(2, f.subscriptions); assertEquals(42, m.person()?.id)
    }

    @Test fun wrongPrimaryIdIsUnavailableAndCannotNavigateOrToggle() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent(); f.api.wrongId = true
        val saved = SavedStateHandle(); val m = f.model(saved = saved); m.setActive(true); runCurrent()
        assertEquals(SectionState.Error(com.techgv.cineverse.presentation.SectionErrorKind.NotAvailable), m.state.value.section)
        var opened = 0
        m.open(m.state.value.context, MediaRef(MediaType.Tv, 701), MobileNavigator { opened++ })
        m.toggleBiography(m.state.value.context)
        assertEquals(0, opened); assertNull(saved.get<Any>("bioExpanded"))
    }

    @Test fun realEnglishFallbackIsMarkedAndWrongEnglishIdIsIgnored() = runTest {
        listOf(false, true).forEach { wrong ->
            val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
            f.api.blankLocalized = true; f.api.englishWrongId = wrong
            f.raw.value = Result.success(Settings(language = "fr-FR"))
            val m = f.model(); m.setActive(true); runCurrent()
            assertEquals(!wrong, m.person()?.biographyInEnglish)
            assertEquals(wrong, m.person()?.biography?.isBlank())
            assertEquals(listOf("42/fr-FR", "42/en-US"), f.api.calls)
            m.close(); f.graph.close()
        }
    }

    @Test fun actualCreditMembershipAndMediaTypeAreRequiredForNavigation() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val m = f.model(); m.setActive(true); runCurrent()
        val destinations = mutableListOf<MobileDestination>(); val nav = MobileNavigator(destinations::add)
        val token = m.state.value.context
        m.open(token, MediaRef(MediaType.Movie, 701), nav)
        m.open(token, MediaRef(MediaType.Movie, 9999), nav)
        m.open(token, MediaRef(MediaType.Tv, 701), nav)
        assertEquals(listOf(MobileDestination.Detail(MediaRef(MediaType.Tv, 701))), destinations)
    }

    @Test fun savedScalarsRestorePerChildAndLoadingNeverClampsThem() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val saved = SavedStateHandle(mapOf("bioExpanded" to true, "person.index" to 20, "person.offset" to 37))
        f.api.gate = CompletableDeferred()
        val m = f.model(saved = saved); m.setActive(true); runCurrent()
        m.saveScroll(m.state.value.context, 0, 0)
        assertEquals(20, saved.get<Int>("person.index")); assertEquals(37, saved.get<Int>("person.offset"))
        f.api.gate!!.complete(Unit); runCurrent()
        assertTrue(m.state.value.expanded)
        m.toggleBiography(m.state.value.context)
        m.saveScroll(m.state.value.context, 18, 19)
        m.setActive(false); m.setActive(true); runCurrent()
        assertFalse(m.state.value.expanded); assertEquals(18, m.state.value.index); assertEquals(19, m.state.value.offset)
    }

    @Test fun hiddenAndClosedCallbacksCannotNavigateToggleSaveOrRetry() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val saved = SavedStateHandle(); val m = f.model(saved = saved); m.setActive(true); runCurrent()
        val old = m.state.value.context
        m.setActive(false)
        var calls = 0
        m.open(old, MediaRef(MediaType.Tv, 701), MobileNavigator { calls++ }); m.back(old) { calls++ }
        m.toggleBiography(old); m.saveScroll(old, 3, 4); m.retry(old); runCurrent()
        assertEquals(0, calls); assertNull(saved.get<Any>("bioExpanded")); assertEquals(0, f.readers)
        m.close(); m.setActive(true); runCurrent(); assertEquals(1, f.subscriptions)
    }

    @Test fun reentrantHideOnLoadingCannotInstallReader() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val f = MobilePersonComponentFixture(scope)
        runCurrent()
        val m = f.model()
        val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            m.state.drop(1).collect { m.setActive(false) }
        }
        m.setActive(true)
        assertEquals(0, f.subscriptions); assertTrue(f.api.calls.isEmpty())
        observer.cancel(); m.close()
    }

    @Test fun reentrantReplacementOnPublicationCannotCancelNewerReader() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val f = MobilePersonComponentFixture(scope)
        runCurrent()
        val m = f.model()
        var replaced = false
        val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            m.state.drop(1).collect {
                if (!replaced) { replaced = true; m.contextChanged() }
            }
        }
        m.setActive(true)
        assertEquals(1, f.readers); assertEquals(1, f.subscriptions); assertEquals(42, m.person()?.id)
        observer.cancel(); m.close()
    }

    @Test fun unconfinedGraphRecoveryDoesNotInstallTransientRawReaders() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val f = MobilePersonComponentFixture(scope)
        runCurrent()
        val m = f.model(); m.setActive(true)
        f.store.fail = true; f.store.values.value = mapOf("language" to "fr-FR")
        assertTrue(m.state.value.settingsFailed); assertEquals(0, f.readers)
        f.store.fail = false; f.store.values.value = emptyMap()
        m.retry(m.state.value.context)
        assertEquals(2, f.subscriptions); assertEquals(1, f.readers); assertEquals(42, m.person()?.id)
    }

    @Test fun reentrantScrollWriteCannotOverwriteOffsetAfterRevocation() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val f = MobilePersonComponentFixture(scope)
        runCurrent()
        val saved = SavedStateHandle(mapOf("person.index" to 1, "person.offset" to 2))
        val m = f.model(saved = saved); m.setActive(true)
        val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            saved.getStateFlow("person.index", 1).collect { if (it == 8) m.setActive(false) }
        }
        m.saveScroll(m.state.value.context, 8, 9)
        assertEquals(8, saved.get<Int>("person.index")); assertEquals(2, saved.get<Int>("person.offset"))
        assertEquals(0, f.readers); assertNull(m.person())
        observer.cancel()
    }

    @Test fun reentrantBiographySaverCannotPublishIntoReplacementHolder() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val f = MobilePersonComponentFixture(scope)
        runCurrent()
        val saved = SavedStateHandle(mapOf("bioExpanded" to false))
        val m = f.model(saved = saved); m.setActive(true)
        val old = m.state.value.context
        val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            saved.getStateFlow("bioExpanded", false).collect { if (it) m.contextChanged() }
        }
        m.toggleBiography(old)
        assertNotEquals(old, m.state.value.context)
        assertTrue(m.state.value.expanded); assertEquals(42, m.person()?.id)
        m.toggleBiography(old)
        assertTrue(saved.get<Boolean>("bioExpanded") == true); assertEquals(1, f.readers)
        observer.cancel()
    }

    @Test fun stubbornHiddenCompletionCannotPublishOrPersistAndReactivationReadsAgain() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent()
        val gate = CompletableDeferred<Unit>(); f.api.gate = gate; f.api.stubborn = true
        val saved = SavedStateHandle(mapOf("person.index" to 12, "person.offset" to 14))
        val m = f.model(saved = saved); m.setActive(true); runCurrent()
        val old = m.state.value.context
        m.setActive(false); gate.complete(Unit); runCurrent()
        m.saveScroll(old, 0, 0); m.toggleBiography(old)
        assertNull(m.person()); assertEquals(12, saved.get<Int>("person.index")); assertNull(saved.get<Any>("bioExpanded"))
        f.api.gate = null; m.setActive(true); runCurrent()
        assertEquals(42, m.person()?.id); assertEquals(2, f.subscriptions)
    }

    @Test fun catalogRetryCreatesFreshHolderAndDoesNotRetryRawFailureImplicitly() = runTest {
        val f = MobilePersonComponentFixture(backgroundScope); runCurrent(); f.api.failure = true
        val m = f.model(); m.setActive(true); runCurrent()
        assertIs<SectionState.Error>(m.state.value.section)
        f.api.failure = false; m.retry(m.state.value.context); runCurrent()
        assertEquals(42, m.person()?.id); assertEquals(2, f.subscriptions)
        f.failRaw(); runCurrent()
        f.raw.value = Result.success(Settings()); runCurrent()
        assertTrue(m.state.value.settingsFailed); assertEquals(2, f.subscriptions)
    }
}
