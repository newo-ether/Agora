package com.newoether.agora.phone

import com.newoether.agora.model.RunEffectIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only lease decisions live here. No lock spans device work, coroutine waits or UI work. */
class PhoneControlLease(private val now: () -> Long) {
    data class Owner(val conversationId: String, val runId: String)
    data class Held(
        val owner: Owner,
        val title: String,
        val epoch: Long,
        val claimedAt: Long,
        val remainingMs: Long,
        val accountedAt: Long,
        val paused: Boolean = false,
    )
    data class Permit(val identity: RunEffectIdentity, val epoch: Long)
    sealed interface Decision {
        data class Granted(val permit: Permit) : Decision
        data class Refused(val code: String, val title: String? = null, val remainingMs: Long? = null) : Decision
    }

    private val lock = Any()
    private var epoch = 0L
    private val revoked = mutableSetOf<Owner>()
    private val releases = mutableMapOf<Owner, String>()
    private val mutableHeld = MutableStateFlow<Held?>(null)
    val held = mutableHeld.asStateFlow()

    /** Reject claims before any readiness probe or platform work. claim() rechecks atomically. */
    fun claimRefusal(identity: RunEffectIdentity): Decision.Refused? = synchronized(lock) {
        if (identity.owner() in revoked) return@synchronized Decision.Refused("released_by_user")
        expireLocked()
        mutableHeld.value?.takeIf { it.owner != identity.owner() }?.let {
            Decision.Refused("occupied", it.title, it.remainingMs)
        }
    }
    fun claim(identity: RunEffectIdentity, title: String): Decision = synchronized(lock) {
        val owner = identity.owner()
        if (owner in revoked) return@synchronized Decision.Refused("released_by_user")
        expireLocked()
        val current = mutableHeld.value
        if (current != null && current.owner != owner) {
            return@synchronized Decision.Refused("occupied", current.title, current.remainingMs)
        }
        if (current == null) {
            releases.remove(owner)
            mutableHeld.value = Held(owner, title, ++epoch, now(), IDLE_MS, now())
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

    /** Exact permit epoch prevents release/reclaim in the same Run accepting an old async result. */
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

    fun revokeByUser(owner: Owner, expectedEpoch: Long) = synchronized(lock) {
        val current = mutableHeld.value
        if (current?.owner == owner && current.epoch == expectedEpoch) {
            revoked += owner
            releaseLocked("released_by_user")
        }
    }

    fun pause(owner: Owner, paused: Boolean) = synchronized(lock) {
        expireLocked()
        val current = mutableHeld.value ?: return@synchronized
        if (current.owner == owner) mutableHeld.value = current.copy(paused = paused, accountedAt = now())
    }

    fun tick() = synchronized(lock) { expireLocked() }

    /** Terminal Run disposal bounds revocation memory. Old tools still fail the canonical Run fence. */
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
        mutableHeld.value?.let { releases[it.owner] = reason }
        mutableHeld.value = null
    }

    companion object { const val IDLE_MS = 300_000L }
}

internal fun RunEffectIdentity.owner() = PhoneControlLease.Owner(conversationId, runId)
