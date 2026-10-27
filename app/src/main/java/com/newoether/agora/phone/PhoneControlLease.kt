package com.newoether.agora.phone

import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.RuntimeRunIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only bounded in-memory lease decisions live here; no lock spans suspended work. */
class PhoneControlLease(
    private val recordRelease: (Release) -> Unit = {},
    private val now: () -> Long,
) {
    data class Owner(val conversationId: String, val runId: String)
    data class Held(
        val owner: Owner,
        val title: String,
        val epoch: Long,
        val claimedAt: Long,
        val remainingMs: Long,
        val accountedAt: Long,
        val paused: Boolean = false,
        val mode: String = "accessibility",
    )
    data class Release(val reason: String, val epoch: Long, val heldMs: Long, val mode: String) {
        val unexpected: Boolean get() = reason !in setOf("released", "released_by_user", "run_ended")
    }
    data class Permit(val identity: RunEffectIdentity, val epoch: Long)
    sealed interface Decision {
        data class Granted(val permit: Permit) : Decision
        data class Refused(val code: String, val title: String? = null, val remainingMs: Long? = null) : Decision
    }
    /** A retained resource, never authority to execute tools or admit a successor Run. */
    private data class Handoff(
        val source: Any,
        val origin: RuntimeRunIdentity,
        val epoch: Long,
        val successor: RuntimeRunIdentity? = null,
    )
    private val lock = Any()
    private var epoch = 0L
    private var handoff: Handoff? = null
    private val revoked = mutableSetOf<Owner>()
    private val releases = mutableMapOf<Owner, String>()
    private val history = ArrayDeque<Release>()
    private val mutableHeld = MutableStateFlow<Held?>(null)
    val held = mutableHeld.asStateFlow()
    fun recentReleases(): List<Release> = synchronized(lock) { history.toList() }
    fun lastUnexpectedRelease(): Release? = synchronized(lock) { history.lastOrNull { it.unexpected } }

    /** Reject claims before any readiness probe or platform work. claim() rechecks atomically. */
    fun claimRefusal(identity: RunEffectIdentity): Decision.Refused? = synchronized(lock) {
        if (identity.owner() in revoked) return@synchronized Decision.Refused("released_by_user")
        expireLocked()
        mutableHeld.value?.takeIf { it.owner != identity.owner() }?.let {
            Decision.Refused("occupied", it.title, it.remainingMs)
        }
    }
    fun claim(identity: RunEffectIdentity, title: String, mode: String = "accessibility"): Decision = synchronized(lock) {
        val owner = identity.owner()
        if (owner in revoked) return@synchronized Decision.Refused("released_by_user")
        expireLocked()
        val current = mutableHeld.value
        if (current != null && current.owner != owner) {
            return@synchronized Decision.Refused("occupied", current.title, current.remainingMs)
        }
        if (current == null) {
            releases.remove(owner)
            mutableHeld.value = Held(owner, title, ++epoch, now(), IDLE_MS, now(), mode = mode)
        } else {
            mutableHeld.value = current.copy(remainingMs = IDLE_MS, accountedAt = now())
        }
        Decision.Granted(Permit(identity, checkNotNull(mutableHeld.value).epoch))
    }
    fun authorize(identity: RunEffectIdentity): Decision = synchronized(lock) {
        if (identity.owner() in revoked) return@synchronized Decision.Refused("released_by_user")
        expireLocked()
        val current = mutableHeld.value
        if (current?.owner != identity.owner()) return@synchronized Decision.Refused("not_claimed")
        Decision.Granted(Permit(identity, current.epoch))
    }
    /** Exact permit epoch rejects old async results even after a reclaim by the same Run. */
    fun validate(permit: Permit): String? = synchronized(lock) {
        if (permit.identity.owner() in revoked) return@synchronized "released_by_user"
        expireLocked()
        val current = mutableHeld.value
        if (current?.owner == permit.identity.owner() && current.epoch == permit.epoch) null
        else releases[permit.identity.owner()] ?: "not_claimed"
    }
    fun complete(permit: Permit): String? = synchronized(lock) {
        val failure = validate(permit)
        if (failure == null) mutableHeld.value = checkNotNull(mutableHeld.value).copy(
            remainingMs = IDLE_MS, accountedAt = now(),
        )
        failure
    }
    fun release(permit: Permit, reason: String = "released"): String? = synchronized(lock) {
        val failure = validate(permit)
        if (failure == null) releaseLocked(reason)
        failure
    }
    fun releaseOwner(owner: Owner, reason: String) = synchronized(lock) {
        if (mutableHeld.value?.owner == owner) releaseLocked(reason)
    }
    fun releaseEpoch(expectedEpoch: Long, reason: String) = synchronized(lock) {
        if (mutableHeld.value?.epoch == expectedEpoch) releaseLocked(reason)
    }
    fun revokeByUser(owner: Owner, expectedEpoch: Long) = synchronized(lock) {
        val current = mutableHeld.value
        if (current?.owner == owner && current.epoch == expectedEpoch) revokeLocked(current)
    }
    /** A notification controls its lease lifetime even when that epoch changed Run owners. */
    internal fun revokeEpochByUser(conversationId: String, expectedEpoch: Long) = synchronized(lock) {
        val current = mutableHeld.value
        if (current?.owner?.conversationId == conversationId && current.epoch == expectedEpoch) revokeLocked(current)
    }
    private fun revokeLocked(current: Held) {
        revoked += current.owner
        releaseLocked("released_by_user")
    }
    fun pause(owner: Owner, paused: Boolean) = synchronized(lock) {
        expireLocked()
        val current = mutableHeld.value ?: return@synchronized
        if (current.owner == owner && current.paused != paused) {
            mutableHeld.value = current.copy(paused = paused, accountedAt = now())
        }
    }
    fun tick() = synchronized(lock) { expireLocked() }
    fun expire(expectedEpoch: Long) = synchronized(lock) {
        if (mutableHeld.value?.epoch == expectedEpoch) expireLocked()
    }
    /** Apply only already accepted reducer transitions; the reducer remains the Run authority. */
    internal fun onRunStateChanged(source: Any, previous: RunState, next: RunState?) = synchronized(lock) {
        val previousIdentity = previous.identity()
        val currentOwner = mutableHeld.value?.owner
        val pending = handoff?.takeIf { it.source === source }
        val ownsPrevious = previousIdentity?.ownerOrNull() == currentOwner && currentOwner != null
        val ownsPending = pending != null && pending.epoch == mutableHeld.value?.epoch
        if (next == null) {
            if (ownsPrevious || ownsPending) releaseLocked("run_ended")
        } else if (ownsPrevious || ownsPending) {
            when {
                next is RunState.Stopping -> releaseLocked("run_ended")
                next is RunState.Finalizing && (next.status != RunStatus.COMPLETED ||
                    next.reason != RunEndReason.CONTINUATION_PENDING || next.persistenceFailureReported) ->
                    releaseLocked("run_ended")
                next is RunState.Finalizing && ownsPrevious &&
                    pending?.origin != next.identity -> {
                    handoff = Handoff(source, next.identity, checkNotNull(mutableHeld.value).epoch)
                }
                ownsPending -> advanceHandoffLocked(checkNotNull(pending), previous, next)
                next is RunState.Active && next.coroutineSettled -> releaseLocked("run_ended")
                next is RunState.Idle -> releaseLocked("run_ended")
            }
        }
        if (previous is RunState.Active && (next !is RunState.Active || next.coroutineSettled)) {
            previousIdentity?.ownerOrNull()?.let { owner -> revoked.remove(owner); releases.remove(owner) }
        }
    }
    /** A no-successor path may retire only its still-unclaimed origin resource. */
    internal fun finishHandoff(source: Any, ownerToken: Long) = synchronized(lock) {
        val pending = handoff ?: return@synchronized
        if (pending.source === source && pending.origin.ownerToken == ownerToken && pending.successor == null &&
            pending.epoch == mutableHeld.value?.epoch
        ) releaseLocked("run_ended")
    }
    private fun advanceHandoffLocked(pending: Handoff, previous: RunState, next: RunState) {
        when {
            next is RunState.Preparing && next.inputFailureReported -> releaseLocked("run_ended")
            previous is RunState.Idle && next is RunState.Preparing ->
                handoff = pending.copy(successor = next.inputEffectIdentity.runIdentity())
            previous is RunState.Idle && next is RunState.Active -> {
                val advanced = pending.copy(successor = next.identity)
                handoff = advanced
                if (next.identity.runId != null) advanceHandoffLocked(advanced, next, next)
            }
            next is RunState.Active && next.identity.runId != null && !next.coroutineSettled -> {
                val expected = pending.successor ?: return
                if (expected.conversationId != next.identity.conversationId ||
                    expected.ownerToken != next.identity.ownerToken || expected.pass != next.identity.pass ||
                    (expected.runId != null && expected.runId != next.identity.runId)
                ) return
                expireLocked()
                val current = mutableHeld.value?.takeIf { it.epoch == pending.epoch } ?: return
                handoff = null
                mutableHeld.value = current.copy(
                    owner = checkNotNull(next.identity.ownerOrNull()), paused = false, accountedAt = now(),
                )
            }
            next is RunState.Active && next.coroutineSettled -> releaseLocked("run_ended")
            next is RunState.Idle && previous !is RunState.Finalizing && previous !is RunState.Idle ->
                releaseLocked("run_ended")
        }
    }
    /** Terminal Run disposal bounds revocation memory. Old tools still fail the Run fence. */
    fun forget(owner: Owner) = synchronized(lock) {
        releaseOwner(owner, "run_ended")
        revoked.remove(owner)
        releases.remove(owner)
    }
    private fun expireLocked() {
        val current = mutableHeld.value ?: return
        val instant = now()
        val remaining = if (current.paused) current.remainingMs
        else current.remainingMs - (instant - current.accountedAt).coerceAtLeast(0)
        if (remaining <= 0) releaseLocked("lease_expired")
        else if (instant != current.accountedAt) mutableHeld.value = current.copy(
            remainingMs = remaining, accountedAt = instant,
        )
    }
    private fun releaseLocked(reason: String) {
        val current = mutableHeld.value ?: return
        val record = Release(reason, current.epoch, (now() - current.claimedAt).coerceAtLeast(0), current.mode)
        releases[current.owner] = reason
        if (history.size == 32) history.removeFirst()
        history.addLast(record)
        handoff = null
        mutableHeld.value = null
        recordRelease(record)
    }
    private fun RunState.identity(): RuntimeRunIdentity? = when (this) {
        is RunState.Active -> identity
        is RunState.Preparing -> ownerIdentity
        is RunState.Finalizing -> identity
        is RunState.Stopping -> identity
        is RunState.Idle, is RunState.Recovering -> null
    }
    private fun RuntimeRunIdentity.ownerOrNull(): Owner? = runId?.let { Owner(conversationId, it) }
    companion object { const val IDLE_MS = 300_000L }
}
internal fun RunEffectIdentity.owner() = PhoneControlLease.Owner(conversationId, runId)
