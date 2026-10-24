package com.techgv.cineverse.data.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Platform boundary. Called on Main; implementations must not keep a dead Activity/window alive. */
interface NativeApprovalOpener {
    fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit)
    fun cancel()
}

enum class NativeApprovalStatus { Idle, AwaitingReturn, Checking, Cancelled, Failed }

/**
 * Mobile integration seam, separate from TV's LoginStateHolder. A callback only requests an exchange;
 * [session] is authoritative even when a cancelled/replaced operation committed just before cancellation.
 * Keep one coordinator for the application; attach/detach the platform presenter at the host boundary.
 */
class NativeApprovalCoordinator(
    private val auth: AuthRepository,
    private val pending: PendingAttemptStore,
    private val opener: NativeApprovalOpener,
    private val createRequestCode: suspend (attemptId: String) -> RequestCode,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ApprovalFlow {
    val session = auth.session
    private val mutableStatus = MutableStateFlow(NativeApprovalStatus.Idle)
    val status = mutableStatus.asStateFlow()
    private val mutableOutcome = MutableStateFlow<SignInOutcome?>(null)
    val outcome = mutableOutcome.asStateFlow()
    private val transitions = Mutex()
    private var work: Deferred<*>? = null
    private val unfinished = mutableListOf<Deferred<*>>()
    private var creating = false
    private var verification: Deferred<SignInOutcome?>? = null

    /** Generate the redirect id first, create its bound token, persist it, then open the platform browser. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun begin(): ApprovalAttempt = replace(createsAttempt = true) {
        opener.cancel()
        mutableOutcome.value = null
        val id = Uuid.random().toHexString()
        val attempt = ApprovalAttempt(id, createRequestCode(id))
        open(attempt)
        attempt
    }

    override suspend fun start(attempt: ApprovalAttempt) = replace(createsAttempt = true) {
        opener.cancel()
        mutableOutcome.value = null
        open(attempt)
    }

    private suspend fun open(attempt: ApprovalAttempt) {
        pending.save(attempt) // durable before a browser/session can produce any callback
        currentCoroutineContext().ensureActive()
        mutableStatus.value = NativeApprovalStatus.AwaitingReturn
        transitions.withLock {
            if (work == currentCoroutineContext()[kotlinx.coroutines.Job]) creating = false
        }
        opener.open(attempt.code.approvalUrl,
            onCallback = { uri -> scope.launch { yield(); callbackSafely(uri) } },
            onCancel = { scope.launch { yield(); cancelIfPending(attempt.id) } },
        )
    }

    suspend fun pendingAttempt(): ApprovalAttempt? = pending.load()

    /** Manual return / timeout retry / cold-start recovery; no redirect delivery is assumed. */
    suspend fun checkAgain(): SignInOutcome? {
        val next = transitions.withLock {
            if (creating && work?.isCompleted == false) return null
            verificationJob()
        }
        return awaitWork(next)
    }

    override suspend fun onCallback(uri: String): Boolean {
        val next = transitions.withLock {
            // Do not interrupt token creation or restart an exchange (its token may already be consumed).
            if ((creating && work?.isCompleted == false) || !pending.isPendingCallback(uri)) return false
            verificationJob()
        }
        awaitWork(next)
        return true
    }

    override suspend fun awaitApproval(attempt: ApprovalAttempt): SignInOutcome {
        val next = transitions.withLock {
            if ((creating && work?.isCompleted == false) || pending.load()?.id != attempt.id) return SignInOutcome.Superseded
            verificationJob()
        }
        return awaitWork(next) ?: SignInOutcome.Superseded
    }

    /** Caller holds transitions; callbacks/manual checks share a single exchange. */
    private fun verificationJob(): Deferred<SignInOutcome?> {
        verification?.takeIf { !it.isCompleted && !it.isCancelled }?.let { return it }
        creating = false
        return schedule { pending.load()?.let { verify(it) } }.also { verification = it }
    }

    private suspend fun verify(attempt: ApprovalAttempt): SignInOutcome {
        if (pending.load()?.id != attempt.id) return SignInOutcome.Superseded
        mutableStatus.value = NativeApprovalStatus.Checking
        val result = auth.awaitApproval(attempt.code)
        if (pending.load()?.id != attempt.id) return SignInOutcome.Superseded
        if (result.consumesAttempt) {
            pending.clear()
            opener.cancel()
        }
        mutableOutcome.value = result
        mutableStatus.value = if (result.consumesAttempt) NativeApprovalStatus.Idle else NativeApprovalStatus.AwaitingReturn
        return result
    }

    private suspend fun cancelIfPending(id: String) {
        val next = transitions.withLock {
            if ((creating && work?.isCompleted == false) || pending.load()?.id != id) return
            verification = null
            schedule {
                opener.cancel()
                mutableStatus.value = NativeApprovalStatus.Cancelled
            }
        }
        awaitWork(next)
    }

    /** Stop/join the exchange and release platform presentation; retain the token for manual return. */
    suspend fun cancel() = replace {
        opener.cancel()
        mutableStatus.value = NativeApprovalStatus.Cancelled
    }

    /** Application teardown only, not Activity recreation. Call on Main. */
    fun close() {
        scope.cancel()
        opener.cancel()
    }

    private suspend fun callbackSafely(uri: String) {
        try {
            onCallback(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableStatus.value = NativeApprovalStatus.Failed
        }
    }

    /** Cancellation/join and all network work run outside the transition mutex. */
    private suspend fun <T> replace(createsAttempt: Boolean = false, block: suspend () -> T): T {
        val next = transitions.withLock {
            creating = createsAttempt
            verification = null
            schedule {
                val result = block()
                // A queued replacement may already own this flag.
                transitions.withLock {
                    if (work == currentCoroutineContext()[kotlinx.coroutines.Job]) creating = false
                }
                result
            }
        }
        return awaitWork(next)
    }

    /** Caller holds transitions, but this lazy coroutine never does during join/network waits. */
    private fun <T> schedule(block: suspend () -> T): Deferred<T> {
        unfinished.removeAll { it.isCompleted }
        val previous = unfinished.toList()
        previous.forEach { it.cancel() }
        return scope.async(start = CoroutineStart.LAZY) {
            previous.joinAll()
            currentCoroutineContext().ensureActive()
            try {
                block()
            } catch (e: CancellationException) {
                mutableStatus.value = NativeApprovalStatus.Cancelled
                throw e
            } catch (e: Exception) {
                mutableStatus.value = NativeApprovalStatus.Failed
                throw e
            }
        }.also { work = it; unfinished += it }
    }

    private suspend fun <T> awaitWork(next: Deferred<T>): T {
        try {
            return next.await()
        } catch (e: CancellationException) {
            next.cancel()
            throw e
        }
    }
}
