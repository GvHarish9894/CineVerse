package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.presentation.Toggle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileProfileViewModelTest {
    @Test fun inactiveOwnerDoesNotReadUntilActivatedAndUnknownIdentityIsNotGuest() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); runCurrent()
        val before = f.store.subscriptions; val model = f.model(); runCurrent()
        assertEquals(before, f.store.subscriptions)
        assertNull(model.state.value.identity)
        var calls = 0
        model.signIn(model.state.value.context) { calls++ }
        model.setActive(true); runCurrent()
        assertEquals(Settings(), model.state.value.settings)
        model.signIn(model.state.value.context) { calls++ }
        assertEquals(0, calls)
        model.close(); f.graph.close()
    }

    @Test fun allSharedTogglesAndStrengthPersistThroughRealRepository() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        for (toggle in Toggle.entries) {
            val token = model.state.value.context
            val before = model.state.value.settings!!
            model.setToggle(token, toggle, !before.profileToggle(toggle)); runCurrent()
            assertEquals(!before.profileToggle(toggle), f.settings.current().profileToggle(toggle))
        }
        for (strength in listOf(HapticStrength.Strong, HapticStrength.Light, HapticStrength.Medium)) {
            val before = model.state.value
            model.cycleStrength(before.context, before.settings!!.strength); runCurrent()
            assertEquals(strength, f.settings.current().strength)
        }
        assertEquals(8, f.store.edits)
        model.close(); f.graph.close()
    }

    @Test fun exactPickerTargetsPersistAndArbitraryCodesAreRejected() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        for ((picker, values) in listOf(MobilePreferencePicker.Language to Settings.LANGUAGES, MobilePreferencePicker.Region to Settings.REGIONS)) {
            val edits = f.store.edits
            model.openPicker(model.state.value.context, picker)
            model.select(model.state.value.context, picker, "invented")
            runCurrent(); assertEquals(edits, f.store.edits)
            assertEquals(picker, model.state.value.picker)
            for (value in values) {
                model.openPicker(model.state.value.context, picker)
                model.select(model.state.value.context, picker, value); runCurrent()
                assertEquals(value, if (picker == MobilePreferencePicker.Language) f.settings.current().language else f.settings.current().region)
                assertNull(model.state.value.picker)
            }
        }
        model.close(); f.graph.close()
    }

    @Test fun failedWriteIsHonestAndReadRecoveryRestartsWithoutReplayingEdit() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        f.store.failWrites = true
        model.setToggle(model.state.value.context, Toggle.Sounds, true); runCurrent()
        assertTrue(model.state.value.writeFailed)
        assertFalse(f.settings.current().sounds)
        val edits = f.store.edits; val subscriptions = f.store.subscriptions
        f.store.failWrites = false
        model.retryReads(model.state.value.context); runCurrent()
        assertTrue(f.store.subscriptions > subscriptions)
        assertEquals(edits, f.store.edits)
        assertFalse(model.state.value.settings!!.sounds)
        assertFalse(model.state.value.writeFailed)
        model.close(); f.graph.close()
    }

    @Test fun failedRawReadNeverBecomesGraphDefaultSuccessAndRetryActuallyResubscribes() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); f.store.fail = true
        val model = f.model(); model.setActive(true); runCurrent()
        assertNull(model.state.value.settings)
        assertTrue(model.state.value.readFailed)
        val subscriptions = f.store.subscriptions
        f.store.fail = false
        model.retryReads(model.state.value.context); runCurrent()
        assertTrue(f.store.subscriptions > subscriptions)
        assertEquals(Settings(), model.state.value.settings)
        assertEquals(0, f.store.edits)
        model.close(); f.graph.close()
    }

    @Test fun coldReaderFailureCannotBorrowCachedGraphSuccess() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); runCurrent()
        assertEquals(Settings(), f.graph.settings.value)
        f.store.fail = true
        val model = f.model(); model.setActive(true); runCurrent()
        assertEquals(Settings(), f.graph.settings.value)
        assertNull(model.state.value.settings); assertTrue(model.state.value.readFailed)
        model.close(); f.graph.close()
    }

    @Test fun failedIdentityIsUnavailableNotGuestAndRecoveryRestartsRealAuthRead() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.fail = true; f.auth.emissions.emit(null)
        model.setActive(true); runCurrent()
        assertTrue(model.state.value.identityFailed); assertNull(model.state.value.identity)
        var calls = 0
        model.signIn(model.state.value.context) { calls++ }
        assertEquals(0, calls)
        f.auth.fail = false; model.retryIdentity(model.state.value.context); runCurrent()
        assertEquals(MobileIdentity(null), model.state.value.identity)
        model.signIn(model.state.value.context) { calls++ }; assertEquals(1, calls)
        model.close(); f.graph.close()
    }

    @Test fun readFailureRevokesRenderedCallbacksAndDoesNotReplayAfterRecovery() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        val rendered = model.state.value
        f.failReads(); runCurrent()
        model.setToggle(rendered.context, Toggle.Sounds, true)
        model.openPicker(rendered.context, MobilePreferencePicker.Language)
        assertNull(model.state.value.settings)
        assertTrue(model.state.value.readFailed)
        f.store.fail = false; model.retryReads(model.state.value.context); runCurrent()
        model.setToggle(rendered.context, Toggle.Sounds, true); runCurrent()
        assertFalse(f.settings.current().sounds); assertEquals(0, f.store.edits)
        model.close(); f.graph.close()
    }

    @Test fun guestSignInAndSignOutRequireExactRenderedFullSession() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        model.setActive(true); runCurrent()
        var signIns = 0; val signOuts = mutableListOf<AuthSession>()
        model.signIn(model.state.value.context) { signIns++ }; assertEquals(0, signIns)
        f.auth.emissions.emit(null); runCurrent()
        val guest = model.state.value.context
        model.signIn(guest) { signIns++ }; assertEquals(1, signIns)
        val first = AuthSession(AccountObjectId("profile-account"), "Actual account", 7)
        f.auth.emissions.emit(first); runCurrent()
        val rendered = model.state.value
        model.signIn(guest) { signIns++ }
        model.signOut(rendered.context, first, signOuts::add)
        assertEquals(listOf(first), signOuts); assertSame(first, signOuts.single())
        val replacement = first.copy(sessionGeneration = 8)
        f.auth.emissions.emit(replacement)
        runCurrent()
        model.signOut(rendered.context, first, signOuts::add)
        model.signOut(model.state.value.context, first, signOuts::add)
        assertEquals(1, signOuts.size); assertEquals(1, signIns)
        model.close(); f.graph.close()
    }

    @Test fun hideCancelsQueuedHolderMutationAndRevokesCallbacksAcrossReentryAndClose() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        val old = model.state.value.context
        model.setToggle(old, Toggle.Sounds, true)
        model.setActive(false); runCurrent()
        assertEquals(0, f.store.edits)
        model.setActive(true); runCurrent()
        model.setToggle(old, Toggle.Sounds, true)
        model.openPicker(old, MobilePreferencePicker.Region)
        model.saveScroll(old, 3, 12)
        assertEquals(0, model.state.value.index)
        val latest = model.state.value.context
        model.close()
        model.setToggle(latest, Toggle.Sounds, true); model.retryReads(latest); runCurrent()
        assertEquals(0, f.store.edits); f.graph.close()
    }

    @Test fun queuedExactPickerEditIsCancelledOnHideAndCannotReplayOnReentry() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        val token = model.state.value.context
        model.openPicker(token, MobilePreferencePicker.Language)
        model.select(token, MobilePreferencePicker.Language, "ta-IN")
        model.setActive(false); runCurrent()
        model.setActive(true); runCurrent()
        assertEquals("en-US", f.settings.current().language); assertEquals(0, f.store.edits)
        model.select(token, MobilePreferencePicker.Language, "ta-IN"); runCurrent()
        assertEquals(0, f.store.edits)
        model.close(); f.graph.close()
    }

    @Test fun generationChangeImmediatelyMasksRenderedStateAndActionsBeforeContextDelivery() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        val rendered = model.state.value
        f.generation.bump()
        assertNull(model.visibleState(rendered).settings)
        assertNull(model.visibleState(rendered).identity)
        var calls = 0
        model.signIn(rendered.context) { calls++ }
        model.setToggle(rendered.context, Toggle.Sounds, true)
        model.openPicker(rendered.context, MobilePreferencePicker.Region)
        model.saveScroll(rendered.context, 4, 37)
        runCurrent(); assertEquals(0, calls); assertEquals(0, f.store.edits)
        model.contextChanged(); runCurrent()
        assertEquals(Settings(), model.state.value.settings)
        assertEquals(0, model.state.value.index)
        model.close(); f.graph.close()
    }

    @Test fun queuedToggleRejectsGenerationChangeAtTransactionAndNeverReplaysAfterRecovery() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        val rendered = model.state.value
        val stored = f.store.values.value
        model.setToggle(rendered.context, Toggle.Sounds, true)
        f.generation.bump()
        runCurrent()
        assertEquals(1, f.store.edits) // The fixture counts attempts before running the transaction transform.
        assertEquals(stored, f.store.values.value)
        assertEquals(rendered.settings, f.settings.current())
        assertFalse(model.state.value.writeFailed)
        model.contextChanged(); runCurrent()
        model.retryReads(model.state.value.context); runCurrent()
        assertEquals(1, f.store.edits)
        assertEquals(stored, f.store.values.value)
        assertEquals(rendered.settings, f.settings.current())
        assertEquals(rendered.settings, model.state.value.settings)
        assertFalse(model.state.value.writeFailed)
        model.close(); f.graph.close()
    }

    @Test fun queuedStrengthRejectsGenerationChangeAtTransactionAndNeverReplaysAfterRecovery() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope); val model = f.model()
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        val rendered = model.state.value
        val stored = f.store.values.value
        model.cycleStrength(rendered.context, rendered.settings!!.strength)
        f.generation.bump()
        runCurrent()
        assertEquals(1, f.store.edits) // The fixture counts attempts before running the transaction transform.
        assertEquals(stored, f.store.values.value)
        assertEquals(rendered.settings, f.settings.current())
        assertFalse(model.state.value.writeFailed)
        model.contextChanged(); runCurrent()
        model.retryReads(model.state.value.context); runCurrent()
        assertEquals(1, f.store.edits)
        assertEquals(stored, f.store.values.value)
        assertEquals(rendered.settings, f.settings.current())
        assertEquals(rendered.settings, model.state.value.settings)
        assertFalse(model.state.value.writeFailed)
        model.close(); f.graph.close()
    }

    @Test fun strictSavedScalarsAndPickerRejectMalformedValuesWithoutRepair() = runTest {
        for ((key, value) in listOf("profile.index" to -1, "profile.index" to 1L, "profile.offset" to "4", "profile.picker" to "Strength", "profile.picker" to 7, "profile.index" to null)) {
            val f = MobileProfileComponentFixture(backgroundScope)
            val saved = SavedStateHandle(mapOf(key to value)); val model = f.model(saved)
            model.setActive(true); runCurrent()
            assertTrue(model.state.value.invalid, "$key=$value")
            model.retryReads(model.state.value.context); runCurrent()
            assertTrue(saved.contains(key)); assertEquals(value, saved.get<Any?>(key)); assertEquals(0, f.store.edits)
            model.close(); f.graph.close()
        }
    }

    @Test fun validPickerAndScrollSurviveEntryRecreationOnlyConfirmedSavesCanChangeThem() = runTest {
        val f = MobileProfileComponentFixture(backgroundScope)
        val saved = SavedStateHandle(mapOf("profile.index" to 4, "profile.offset" to 37, "profile.picker" to "Region"))
        val model = f.model(saved)
        model.saveScroll(model.state.value.context, 0, 0)
        assertEquals(4, saved.get<Int>("profile.index"))
        f.auth.emissions.emit(null); model.setActive(true); runCurrent()
        assertEquals(MobilePreferencePicker.Region, model.state.value.picker)
        model.saveScroll(model.state.value.context, -1, 0)
        assertEquals(4, saved.get<Int>("profile.index"))
        model.saveScroll(model.state.value.context, 3, 12)
        model.close()
        val replacement = f.model(saved); replacement.setActive(true); runCurrent()
        assertEquals(3, replacement.state.value.index); assertEquals(12, replacement.state.value.offset)
        replacement.close(); f.graph.close()
    }
}
