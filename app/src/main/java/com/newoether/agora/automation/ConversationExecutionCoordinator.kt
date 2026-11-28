package com.newoether.agora.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped serialization and priority gate for conversation mutations that include
 * generation.
 *
 * Foreground chat and headless automation share one entry per conversation, so they cannot resolve
 * the same leaf and append sibling turns concurrently. Automation waiters have strict priority over
 * foreground waiters when the current owner releases; this makes the product rule `cron > queued
 * user send` structural instead of depending on coroutine/Mutex scheduling order. FIFO is preserved
 * within each priority class, and different conversations remain independent.
 *
 * Entries are reference counted and cancellation-safe, so task-created conversation ids do not
 * accumulate forever. The coordinator is intentionally non-reentrant for a given id.
 */
class ConversationExecutionCoordinator(
    private val parentConversationId: suspend (String) -> String? = { null },
    private val subagentMaxConcurrent: StateFlow<Int> = MutableStateFlow(4),
) {
    private class GenerationAdmission(val parentId: String, var references: Int = 1)

    private val generationAdmissions = mutableMapOf<String, GenerationAdmission>()
    private val generationChanges = MutableStateFlow(0L)
    private val _admittedGenerationIds = MutableStateFlow<Set<String>>(emptySet())
    val admittedGenerationIds: StateFlow<Set<String>> = _admittedGenerationIds.asStateFlow()

    /**
     * A worker retains this reference across ordinary/Compact handoffs. Installed generation Jobs
     * retain their own reference until the canonical slot and Job barriers have both settled.
     * Only generation callers use this API; graph reads, recovery and deletion keep plain locks.
     */
    suspend fun <T> withGenerationAdmission(conversationId: String, block: suspend () -> T): T {
        val release = acquireGenerationAdmission(conversationId)
        return try {
            block()
        } finally {
            release()
        }
    }

    /** Demand-loads only this owner's durable parent; an ordinary parent consumes no allowance. */
    suspend fun acquireGenerationAdmission(conversationId: String): () -> Unit {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        // A handoff may run in a separately scoped Job. Reuse the durable-parent admission already
        // held by this child without suspending through another Room observation before retaining it.
        val retained = synchronized(monitor) {
            generationAdmissions[conversationId]?.also { it.references += 1 }
        }
        if (retained != null) return releaseGenerationAdmission(conversationId, retained)
        val parentId = parentConversationId(conversationId) ?: return {}
        require(parentId != conversationId && parentId.isNotBlank())
        while (true) {
            val revision = generationChanges.value
            val maximum = subagentMaxConcurrent.value.coerceIn(1, 16)
            val admission = synchronized(monitor) {
                val existing = generationAdmissions[conversationId]
                if (existing != null) {
                    check(existing.parentId == parentId) { "Generation parent changed while admitted" }
                    existing.references += 1
                    existing
                } else if (generationAdmissions.values.count { it.parentId == parentId } < maximum) {
                    GenerationAdmission(parentId).also {
                        generationAdmissions[conversationId] = it
                        _admittedGenerationIds.value = generationAdmissions.keys.toSet()
                    }
                } else {
                    null
                }
            }
            if (admission != null) {
                val release = releaseGenerationAdmission(conversationId, admission)
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    release()
                    throw cancelled
                }
                return release
            }
            // No second queue or polling worker: release and settings changes wake this waiter.
            combine(generationChanges, subagentMaxConcurrent) { changed, limit ->
                changed != revision || limit.coerceIn(1, 16) != maximum
            }.first { it }
        }
    }

    /** Atomically reserves a tool-requested child before any creation or queue mutation. */
    fun reserveSubagentGeneration(parentId: String, conversationId: String): () -> Unit {
        require(parentId.isNotBlank() && conversationId.isNotBlank() && parentId != conversationId)
        val admission = synchronized(monitor) {
            val existing = generationAdmissions[conversationId]
            if (existing != null) {
                check(existing.parentId == parentId) { "Generation parent changed while admitted" }
                existing.references += 1
                existing
            } else {
                val maximum = subagentMaxConcurrent.value.coerceIn(1, 16)
                val running = generationAdmissions.values.count { it.parentId == parentId }
                check(running < maximum) {
                    "Subagent concurrency limit reached ($running/$maximum running for this conversation). " +
                        "Wait for a running subagent to finish (agent_wait) before starting another."
                }
                GenerationAdmission(parentId).also {
                    generationAdmissions[conversationId] = it
                    _admittedGenerationIds.value = generationAdmissions.keys.toSet()
                }
            }
        }
        return releaseGenerationAdmission(conversationId, admission)
    }

    private fun releaseGenerationAdmission(
        conversationId: String,
        admission: GenerationAdmission,
    ): () -> Unit {
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) synchronized(monitor) {
                check(generationAdmissions[conversationId] === admission)
                check(--admission.references >= 0)
                if (admission.references == 0) {
                    generationAdmissions.remove(conversationId)
                    _admittedGenerationIds.value = generationAdmissions.keys.toSet()
                    generationChanges.update { it + 1 }
                }
            }
            Unit
        }
    }

    internal fun admittedChildCount(parentId: String): Int = synchronized(monitor) {
        generationAdmissions.values.count { it.parentId == parentId }
    }

    private class Waiter(val automation: Boolean) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
    }

    private class Entry {
        var owner: Waiter? = null
        val automationWaiters = ArrayDeque<Waiter>()
        val foregroundWaiters = ArrayDeque<Waiter>()
        var references: Int = 0
    }

    private data class Lease(
        val conversationId: String,
        val entry: Entry,
        val waiter: Waiter,
    )

    private val monitor = Any()
    private val entries = mutableMapOf<String, Entry>()

    private val _activeConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val activeConversationIds: StateFlow<Set<String>> = _activeConversationIds.asStateFlow()

    private val _activeAutomationConversationIds =
        MutableStateFlow<Set<String>>(emptySet())
    /** Conversation ids currently owned by Task/Loop execution, excluding foreground mutations. */
    val activeAutomationConversationIds: StateFlow<Set<String>> =
        _activeAutomationConversationIds.asStateFlow()

    suspend fun <T> withConversationLock(
        conversationId: String,
        block: suspend () -> T,
    ): T = withPriorityLock(conversationId, automation = false, block = block)

    suspend fun <T> withAutomationConversationLock(
        conversationId: String,
        block: suspend () -> T,
    ): T = withPriorityLock(conversationId, automation = true, block = block)

    /** Runs foreground admission only when no process owner already holds or awaits this id. */
    suspend fun tryWithConversationLock(
        conversationId: String,
        block: suspend () -> Unit,
    ): Boolean {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val waiter = Waiter(automation = false)
        val entry = synchronized(monitor) {
            if (entries.containsKey(conversationId)) return@synchronized null
            Entry().also { created ->
                created.owner = waiter
                created.references = 1
                waiter.granted = true
                entries[conversationId] = created
            }
        } ?: return false
        val lease = Lease(conversationId, entry, waiter)
        publishAcquired(lease)
        return try {
            block()
            true
        } finally {
            release(lease, published = true)
        }
    }

    private suspend fun <T> withPriorityLock(
        conversationId: String,
        automation: Boolean,
        block: suspend () -> T,
    ): T {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val lease = acquire(conversationId, automation)
        publishAcquired(lease)
        return try {
            block()
        } finally {
            release(lease, published = true)
        }
    }

    private suspend fun acquire(conversationId: String, automation: Boolean): Lease {
        val waiter = Waiter(automation)
        val entry = synchronized(monitor) {
            entries.getOrPut(conversationId) { Entry() }.also { current ->
                current.references += 1
                if (current.owner == null) {
                    current.owner = waiter
                    waiter.granted = true
                } else if (automation) {
                    current.automationWaiters.addLast(waiter)
                } else {
                    current.foregroundWaiters.addLast(waiter)
                }
            }
        }
        val lease = Lease(conversationId, entry, waiter)
        if (!waiter.granted) {
            try {
                waiter.ready.await()
            } catch (cancelled: CancellationException) {
                val wasGranted = synchronized(monitor) {
                    if (waiter.granted) {
                        true
                    } else {
                        val removed = if (automation) {
                            entry.automationWaiters.remove(waiter)
                        } else {
                            entry.foregroundWaiters.remove(waiter)
                        }
                        check(removed) { "Cancelled conversation waiter was not queued" }
                        entry.references -= 1
                        removeEntryIfUnused(conversationId, entry)
                        false
                    }
                }
                // Grant and cancellation can race. If ownership was already transferred to this
                // cancelled waiter, hand it on immediately so the conversation cannot deadlock.
                if (wasGranted) release(lease, published = false)
                throw cancelled
            }
        }
        return lease
    }

    private fun publishAcquired(lease: Lease) {
        _activeConversationIds.update { it + lease.conversationId }
        if (lease.waiter.automation) {
            _activeAutomationConversationIds.update { it + lease.conversationId }
        }
    }

    private fun release(lease: Lease, published: Boolean) {
        val next = synchronized(monitor) {
            check(lease.entry.owner === lease.waiter) {
                "Conversation lock released by a non-owner"
            }
            lease.entry.references -= 1
            check(lease.entry.references >= 0) {
                "Conversation lock reference count underflow"
            }
            val selected = when {
                lease.entry.automationWaiters.isNotEmpty() ->
                    lease.entry.automationWaiters.removeFirst()
                lease.entry.foregroundWaiters.isNotEmpty() ->
                    lease.entry.foregroundWaiters.removeFirst()
                else -> null
            }
            lease.entry.owner = selected
            selected?.granted = true
            removeEntryIfUnused(lease.conversationId, lease.entry)
            selected
        }

        if (published) {
            _activeConversationIds.update { it - lease.conversationId }
            if (lease.waiter.automation) {
                _activeAutomationConversationIds.update { it - lease.conversationId }
            }
        }
        next?.ready?.complete(Unit)
    }

    private fun removeEntryIfUnused(conversationId: String, entry: Entry) {
        if (entry.references == 0) {
            check(entry.owner == null)
            check(entry.automationWaiters.isEmpty() && entry.foregroundWaiters.isEmpty())
            entries.remove(conversationId, entry)
        }
    }

    fun isExecuting(conversationId: String): Boolean =
        conversationId in activeConversationIds.value

    internal fun trackedConversationCount(): Int = synchronized(monitor) { entries.size }
}
