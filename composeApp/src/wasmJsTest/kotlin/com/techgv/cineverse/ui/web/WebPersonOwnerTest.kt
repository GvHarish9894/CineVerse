package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebPersonOwnerTest {
    @Test fun immutableCapturedInputUsesOnlyActualPersonLanguageTuple() = runTest {
        val f = WebPersonFixture()
        val original = Settings(language = "ta-IN")
        f.captured = original
        val owner = f.owner(backgroundScope)
        runCurrent()
        val first = owner.state.value.context!!
        assertSame(original, first.settings)
        assertEquals(listOf("ta-IN"), f.api.requests.map { it.language })
        assertEquals(42, f.api.requests.single().id)
        assertEquals("combined_credits", f.api.requests.single().append)
        f.emit(original.copy(region = "GB", includeAdult = true, motion = true, glow = false))
        runCurrent()
        assertSame(first, owner.state.value.context)
        assertSame(original, first.settings)
        assertEquals(1, f.api.requests.size)
        val spanish = original.copy(language = "es-ES")
        f.emit(spanish)
        runCurrent()
        assertNotSame(first, owner.state.value.context)
        assertSame(spanish, owner.state.value.context!!.settings)
        assertEquals(listOf("ta-IN", "es-ES"), f.api.requests.map { it.language })
    }

    @Test fun unknownRawInputNeverConstructsADefaultRead() = runTest {
        val f = WebPersonFixture().apply { captured = null }
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertEquals(WebPersonReader.Unknown, owner.state.value.reader)
        assertFalse(owner.state.value.verified)
        assertNull(owner.state.value.context)
        assertEquals(0, f.api.requests.size)
        f.emit(Settings(language = "hi-IN"))
        runCurrent()
        assertEquals("hi-IN", f.api.requests.single().language)
        assertTrue(owner.state.value.verified)
    }

    @Test fun rawFailureRetainsOnlyRealContentAndReportsSanitizedTypedFailure() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val real = owner.state.value.displayed
        val context = owner.state.value.context
        f.fail(StorageException("private raw read message"))
        runCurrent()
        val failed = owner.state.value
        assertEquals(WebPersonReader.Failed, failed.reader)
        assertEquals(BrowserStorageFailure.Unavailable, failed.settingsFailure)
        assertFalse(failed.verified)
        assertNull(failed.context)
        assertSame(real, failed.displayed)
        assertSame(context, failed.retainedContext)
        assertEquals(BrowserStorageFailure.Unavailable, (f.reports.single() as BrowserStorageException).failure)
        assertFalse(f.reports.single().message.orEmpty().contains("private"))
        owner.openTitle(failed, MediaRef(MediaType.Movie, 7))
        owner.toggleBiography(failed)
        owner.saveScroll(failed, 8, 12)
        assertTrue(f.destinations.isEmpty())
        assertNull(f.entry.getString("bioExpanded"))
        assertNull(f.entry.getString("person.index"))
    }

    @Test fun equalColdRecoveryCannotExposeOldCompletionWhileReplacementPrimaryIsBlocked() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val old = owner.state.value
        f.fail()
        runCurrent()
        f.failure = null
        f.evict()
        val gate = CompletableDeferred<Unit>()
        f.api.respond = { id, _ -> gate.await(); webPersonDto(id) }
        val observed = mutableListOf<WebPersonReadState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { observed += it } }
        f.retryHook = {
            assertEquals(WebPersonReader.Unknown, owner.state.value.reader)
            assertNull(owner.state.value.context)
            assertFalse(owner.state.value.verified)
        }
        owner.retryReads()
        runCurrent()
        val pending = owner.state.value
        assertEquals(2, f.subscriptions)
        assertEquals(1, f.retries)
        assertEquals(2, f.api.requests.size)
        assertNotSame(old.context, pending.context)
        assertSame(old.displayed, pending.displayed)
        assertSame(old.context, pending.retainedContext)
        assertTrue(observed.filter { it.reader == WebPersonReader.Ready }.all { !it.verified })
        assertFalse(pending.primaryCompleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.verified)
        assertSame(pending.context, owner.state.value.retainedContext)
        assertEquals(old.displayed, owner.state.value.displayed)
    }

    @Test fun equalCacheHitRecoveryStillOwnsANewHolderAndRealColdSubscription() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val old = owner.state.value
        val snapshots = mutableListOf<WebPersonReadState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { snapshots += it } }
        owner.retryReads()
        assertFalse(owner.state.value.verified)
        assertNull(owner.state.value.context)
        runCurrent()
        val recovered = owner.state.value
        assertEquals(2, f.subscriptions)
        assertEquals(1, f.retries)
        assertEquals(1, f.api.requests.size) // Existing repository cache, not a fabricated HTTP-fresh claim.
        assertNotSame(old.context, recovered.context)
        assertTrue(recovered.context!!.readerEpoch > old.context!!.readerEpoch)
        assertTrue(snapshots.any { it.context === recovered.context && !it.primaryCompleted })
        assertTrue(recovered.verified)
        assertEquals(old.displayed, recovered.displayed)
    }

    @Test fun externalStorageRecoveryRestartsTerminatedRawReaderEvenWhenEqual() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val first = owner.state.value.context
        f.storage.value = BrowserStorageFailure.Corrupt
        f.fail()
        runCurrent()
        assertEquals(WebPersonReader.Failed, owner.state.value.reader)
        f.failure = null
        f.storage.value = null
        runCurrent()
        assertEquals(2, f.subscriptions)
        assertNotSame(first, owner.state.value.context)
        assertTrue(owner.state.value.verified)
        assertEquals(0, f.retries) // Root already performed upstream recovery.
    }

    @Test fun catalogRetryUsesFreshInstanceButDoesNotReplayStorageOrAnyMutation() = runTest {
        val f = WebPersonFixture()
        f.api.respond = { _, _ -> throw TmdbException(TmdbError.Network) }
        val owner = f.owner(backgroundScope)
        runCurrent()
        val failed = owner.state.value
        assertEquals(SectionState.Error(SectionErrorKind.Offline), failed.section)
        val gate = CompletableDeferred<Unit>()
        f.api.respond = { id, _ -> gate.await(); webPersonDto(id) }
        owner.retryPerson(failed)
        runCurrent()
        assertNotSame(failed.context, owner.state.value.context)
        assertFalse(owner.state.value.verified)
        assertEquals(1, f.subscriptions)
        assertEquals(0, f.retries)
        gate.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.verified)
    }

    @Test fun cancellationResistantOldPrimaryCannotPublishIntoReplacementOwnerToken() = runTest {
        val f = WebPersonFixture()
        val oldGate = CompletableDeferred<Unit>()
        var oldReturned = false
        f.api.respond = { id, language ->
            if (language == "en-US") withContext(NonCancellable) {
                oldGate.await()
                oldReturned = true
                webPersonDto(id, "Late old primary")
            } else webPersonDto(id, "Current Spanish primary")
        }
        val owner = f.owner(backgroundScope)
        runCurrent()
        val old = owner.state.value
        f.emit(Settings(language = "es-ES"))
        runCurrent()
        val replacement = owner.state.value
        assertTrue(replacement.verified) // Replacement did not join the stubborn old job.
        assertEquals("Current Spanish primary", replacement.displayed!!.biography)
        oldGate.complete(Unit)
        runCurrent()
        assertTrue(oldReturned)
        assertSame(replacement, owner.state.value)
        owner.openTitle(old, MediaRef(MediaType.Movie, 7))
        assertTrue(f.destinations.isEmpty())
    }

    @Test fun rawFailureRevokesCancellationResistantInFlightPrimaryWithoutInventingRetainedContent() = runTest {
        val f = WebPersonFixture()
        val gate = CompletableDeferred<Unit>()
        f.api.respond = { id, _ -> withContext(NonCancellable) { gate.await(); webPersonDto(id) } }
        val owner = f.owner(backgroundScope)
        runCurrent()
        f.fail()
        runCurrent()
        val failed = owner.state.value
        assertNull(failed.retained)
        gate.complete(Unit)
        runCurrent()
        assertSame(failed, owner.state.value)
        assertNull(owner.state.value.displayed)
    }

    @Test fun englishFallbackUpdatesOnlyActualInstanceWithoutBecomingANewCompletion() = runTest {
        val f = WebPersonFixture().apply { captured = Settings(language = "ta-IN") }
        val english = CompletableDeferred<Unit>()
        f.api.respond = { id, language ->
            if (language == "en-US") { english.await(); webPersonDto(id, "English fixture biography") }
            else webPersonDto(id, "")
        }
        val owner = f.owner(backgroundScope)
        runCurrent()
        val primary = owner.state.value
        assertTrue(primary.verified)
        assertEquals("", primary.displayed!!.biography)
        assertFalse(primary.displayed!!.biographyInEnglish)
        english.complete(Unit)
        runCurrent()
        assertSame(primary.context, owner.state.value.context)
        assertTrue(owner.state.value.displayed!!.biographyInEnglish)
        assertEquals("English fixture biography", owner.state.value.displayed!!.biography)
        assertEquals(listOf("ta-IN", "en-US"), f.api.requests.map { it.language })
    }

    @Test fun lateEnglishFallbackCannotReplaceNewLanguageAndOptionalFailureKeepsPrimary() = runTest {
        val f = WebPersonFixture().apply { captured = Settings(language = "ta-IN") }
        val english = CompletableDeferred<Unit>()
        f.api.respond = { id, language -> when (language) {
            "en-US" -> withContext(NonCancellable) { english.await(); webPersonDto(id, "Late English") }
            "ta-IN" -> webPersonDto(id, "")
            else -> webPersonDto(id, "Spanish")
        } }
        val owner = f.owner(backgroundScope)
        runCurrent()
        f.emit(Settings(language = "es-ES"))
        runCurrent()
        val current = owner.state.value
        english.complete(Unit)
        runCurrent()
        assertSame(current, owner.state.value)
        assertEquals("Spanish", current.displayed!!.biography)
        assertFalse(current.displayed!!.biographyInEnglish)
        owner.close()
        f.evict()
        f.emit(Settings(language = "hi-IN"))
        f.api.respond = { id, language -> if (language == "en-US") throw TmdbException(TmdbError.Network) else webPersonDto(id, "") }
        val second = f.owner(backgroundScope)
        runCurrent()
        assertTrue(second.state.value.verified)
        assertEquals("", second.state.value.displayed!!.biography)
        assertFalse(second.state.value.displayed!!.biographyInEnglish)
    }

    @Test fun idleSnapshotRequiresRealRootEventAndThenRetainsStaleUntilReplacement() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val old = owner.state.value
        f.generation.bump()
        runCurrent()
        assertSame(old, owner.state.value) // GenerationCounter deliberately has no invented observable poller.
        f.evict()
        val gate = CompletableDeferred<Unit>()
        f.api.respond = { id, _ -> gate.await(); webPersonDto(id) }
        owner.invalidateContext() // Actual root focus/session/generation event.
        assertFalse(owner.state.value.verified)
        runCurrent()
        assertEquals(f.generation.current, owner.state.value.context!!.appSnapshot)
        assertSame(old.displayed, owner.state.value.displayed)
        assertFalse(owner.state.value.verified)
        gate.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.verified)
    }

    @Test fun clickTimeSnapshotMismatchCannotNavigateToggleOrSaveAndStartsActualRevalidation() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val old = owner.state.value
        f.generation.bump()
        owner.openTitle(old, MediaRef(MediaType.Movie, 7))
        owner.toggleBiography(old)
        owner.saveScroll(old, 5, 30)
        owner.back(old)
        assertTrue(f.destinations.isEmpty())
        assertEquals(0, f.backs)
        assertNull(f.entry.getString("bioExpanded"))
        assertNull(f.entry.getString("person.index"))
        runCurrent()
        assertTrue(f.subscriptions > 1)
        assertEquals(f.generation.current, owner.state.value.context!!.appSnapshot)
    }

    @Test fun reentrantContextPublicationDisposalPreventsHolderConstructionAndIo() = runTest {
        val f = WebPersonFixture().apply { captured = null }
        val owner = f.owner(backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { if (it.context != null) owner.close() }
        }
        runCurrent()
        f.emit(Settings())
        runCurrent()
        assertEquals(0, f.api.requests.size)
        owner.retryReads()
        owner.invalidateContext()
        assertEquals(0, f.retries)
        assertEquals(1, f.subscriptions)
    }

    @Test fun reentrantRawReadyObserverCannotRetryBeforeAnActualHolderContextExists() = runTest {
        val f = WebPersonFixture().apply { captured = null }
        val owner = f.owner(backgroundScope)
        val contexts = mutableSetOf<WebPersonContext>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect {
                it.context?.let(contexts::add)
                if (it.reader == WebPersonReader.Ready && it.context == null) owner.retryPerson(it)
            }
        }
        runCurrent()
        f.emit(Settings())
        runCurrent()
        assertEquals(1, contexts.size)
        assertEquals(1, f.api.requests.size)
        assertTrue(owner.state.value.verified)
    }

    @Test fun reentrantCatalogCancellationDisposalCannotPublishOrConstructReplacementHolder() = runTest {
        val f = WebPersonFixture()
        lateinit var owner: WebPersonOwner
        f.api.respond = { _, _ -> suspendCancellableCoroutine<PersonDetailDto> { continuation ->
            continuation.invokeOnCancellation { owner.close() }
        } }
        owner = f.owner(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        runCurrent()
        val source = owner.state.value
        assertEquals(1, f.api.requests.size)
        owner.retryPerson(source)
        runCurrent()
        assertEquals(1, f.api.requests.size)
        assertSame(source, owner.state.value)
    }

    @Test fun reentrantCancellationRootEventCannotHaveItsNewReaderCanceledBySupersededRetry() = runTest {
        val f = WebPersonFixture()
        lateinit var owner: WebPersonOwner
        f.api.respond = { id, _ ->
            if (f.api.requests.size == 1) suspendCancellableCoroutine<PersonDetailDto> { continuation ->
                continuation.invokeOnCancellation { owner.invalidateContext() }
            } else webPersonDto(id)
        }
        owner = f.owner(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        runCurrent()
        owner.retryReads()
        runCurrent()
        assertEquals(2, f.subscriptions)
        assertEquals(2, f.api.requests.size)
        assertEquals(0, f.retries)
        assertTrue(owner.state.value.verified)
    }

    @Test fun reentrantUpstreamRetryDisposalCannotSubscribeOrProduceNavigationAndSaverFeedback() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val old = owner.state.value
        val encoded = f.entry.encoded()
        f.retryHook = owner::close
        owner.retryReads()
        owner.invalidateContext()
        owner.retryPerson(old)
        owner.toggleBiography(old)
        owner.openTitle(old, MediaRef(MediaType.Movie, 7))
        owner.saveScroll(old, 7, 25)
        owner.back(old)
        runCurrent()
        assertEquals(1, f.subscriptions)
        assertEquals(1, f.api.requests.size)
        assertEquals(1, f.retries)
        assertEquals(encoded, f.entry.encoded())
        assertTrue(f.destinations.isEmpty())
        assertEquals(0, f.backs)
    }

    @Test fun parentOnlyCancellationGuardsSynchronousMethodsAndQueuedInitialReader() = runTest {
        val f = WebPersonFixture()
        val parent = Job()
        val owner = f.owner(CoroutineScope(coroutineContext + parent))
        parent.cancel()
        owner.retryReads()
        owner.invalidateContext()
        owner.back(owner.state.value)
        runCurrent()
        assertEquals(0, f.subscriptions)
        assertEquals(0, f.api.requests.size)
        assertEquals(0, f.retries)
        assertEquals(0, f.backs)
    }

    @Test fun canceledLoadedOwnerCannotPublishLateWorkOrWriteFromSynchronousActions() = runTest {
        val f = WebPersonFixture()
        val parent = Job()
        val owner = f.owner(CoroutineScope(coroutineContext + parent))
        runCurrent()
        val source = owner.state.value
        parent.cancel()
        owner.openTitle(source, MediaRef(MediaType.Movie, 7))
        owner.toggleBiography(source)
        owner.saveScroll(source, 3, 9)
        owner.retryPerson(source)
        owner.retryReads()
        owner.back(source)
        runCurrent()
        assertTrue(f.destinations.isEmpty())
        assertEquals(0, f.backs)
        assertEquals(0, f.retries)
        assertEquals("{}", f.entry.encoded())
    }

    @Test fun presentInvalidCanonicalRouteAndSavedScalarsRejectBeforeAnySubscriptionOrIo() = runTest {
        val f = WebPersonFixture()
        val invalid: List<Pair<WebRoute, Map<String, String>>> = listOf(
            WebRoute.Home to emptyMap(), WebRoute.Person(0) to emptyMap(), WebRoute.Person(-1) to emptyMap(),
            WebRoute.Person(42) to mapOf("person.id" to "43"), WebRoute.Person(42) to mapOf("person.id" to "042"),
            WebRoute.Person(42) to mapOf("person.id" to "null"), WebRoute.Person(42) to mapOf("bioExpanded" to "TRUE"),
            WebRoute.Person(42) to mapOf("bioExpanded" to "0"), WebRoute.Person(42) to mapOf("bioExpanded" to ""),
            WebRoute.Person(42) to mapOf("person.index" to "-1"), WebRoute.Person(42) to mapOf("person.index" to "01"),
            WebRoute.Person(42) to mapOf("person.offset" to "1.5"), WebRoute.Person(42) to mapOf("person.offset" to "2147483648"),
        )
        for ((route, values) in invalid) {
            val entry = WebEntry(route, values.toMutableMap())
            val before = entry.encoded()
            assertFailsWith<IllegalArgumentException> { f.owner(backgroundScope, entry) }
            assertEquals(before, entry.encoded())
        }
        runCurrent()
        assertEquals(0, f.subscriptions)
        assertEquals(0, f.api.requests.size)
    }

    @Test fun wrongPrimaryPersonIdentityIsAnErrorNotDisplayOrNavigationAuthority() = runTest {
        val f = WebPersonFixture()
        f.api.respond = { _, _ -> webPersonDto(99) }
        val owner = f.owner(backgroundScope)
        runCurrent()
        val state = owner.state.value
        assertEquals(SectionState.Error(SectionErrorKind.Generic), state.section)
        assertFalse(state.verified)
        assertNull(state.displayed)
        assertNull(state.retainedContext)
        owner.openTitle(state, MediaRef(MediaType.Movie, 7))
        assertTrue(f.destinations.isEmpty())
    }

    @Test fun unsupportedRawLanguageFailsTypedWithoutDefaultOrCatalogRead() = runTest {
        val f = WebPersonFixture().apply { captured = Settings(language = "invalid") }
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertEquals(WebPersonReader.Failed, owner.state.value.reader)
        assertEquals(BrowserStorageFailure.Corrupt, owner.state.value.settingsFailure)
        assertEquals(0, f.api.requests.size)
    }

    @Test fun postConstructionInvalidSaverIsPreservedAndCannotTriggerNewReadsOrWrites() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val source = owner.state.value
        f.entry.putString("bioExpanded", "not-a-boolean")
        val before = f.entry.encoded()
        owner.toggleBiography(source)
        owner.openTitle(source, MediaRef(MediaType.Movie, 7))
        owner.saveScroll(source, 8, 11)
        owner.retryPerson(source)
        owner.invalidateContext()
        runCurrent()
        assertEquals(before, f.entry.encoded())
        assertTrue(f.destinations.isEmpty())
        assertEquals(1, f.api.requests.size)
        assertEquals(1, f.subscriptions)
    }

    @Test fun realCreditMediaAndIdsOnlyNeverPersonIdOrArbitraryRef() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val source = owner.state.value
        assertEquals(2, source.displayed!!.creditCount)
        assertEquals(2, source.displayed!!.credits.size)
        owner.openTitle(source, MediaRef(MediaType.Movie, 42))
        owner.openTitle(source, MediaRef(MediaType.Movie, -1))
        owner.openTitle(source, MediaRef(MediaType.Tv, 7))
        owner.openTitle(source, MediaRef(MediaType.Movie, 7))
        owner.openTitle(source, MediaRef(MediaType.Tv, 9))
        owner.back(source)
        assertEquals(listOf(MediaRef(MediaType.Movie, 7), MediaRef(MediaType.Tv, 9)), f.destinations)
        assertEquals(1, f.backs)
    }

    @Test fun exactCommonBiographyScalarRestoresTrueAndFalseAcrossNewInstances() = runTest {
        val f = WebPersonFixture()
        for (initial in listOf(true, false)) {
            f.entry.putString("person.id", "42")
            f.entry.putBoolean("bioExpanded", initial)
            val owner = f.owner(backgroundScope)
            runCurrent()
            assertEquals(initial, owner.state.value.bioExpanded)
            owner.toggleBiography(owner.state.value)
            assertEquals(!initial, owner.state.value.bioExpanded)
            assertEquals((!initial).toString(), f.entry.getString("bioExpanded"))
            owner.close()
            val restored = f.owner(backgroundScope)
            runCurrent()
            assertEquals(!initial, restored.state.value.bioExpanded)
            restored.close()
        }
    }

    @Test fun reentrantSaverDisposalStopsFurtherScrollWritesAndOwnerPublication() = runTest {
        val f = WebPersonFixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val source = owner.state.value
        f.entry.changed = owner::close
        owner.saveScroll(source, 6, 37)
        assertEquals("6", f.entry.getString("person.index"))
        assertNull(f.entry.getString("person.offset"))
        owner.toggleBiography(source)
        assertNull(f.entry.getString("bioExpanded"))
        assertSame(source, owner.state.value)
    }
}
