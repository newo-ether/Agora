package com.newoether.agora.data

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.local.UsageLedgerDao
import com.newoether.agora.data.local.UsageLedgerRow
import com.newoether.agora.data.local.UsageLedgerSource
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide writer of the request-time usage ledger.
 *
 * Every Provider request reports here once when it ends. Writes are queued and applied in order
 * off the caller's thread, so recording never delays a generation. Before [initialize] runs (unit
 * tests, a failed database gate) records are dropped.
 */
object UsageRecorder {
    private data class Pending(
        val atMillis: Long,
        val provider: String,
        val model: String,
        val source: String?,
        val conversationId: String?,
        val usage: TokenUsage?,
    )

    @Volatile
    private var queue: Channel<Pending>? = null

    private val _revision = MutableStateFlow(0L)
    /** Increments after each applied write so an open Usage page can reload. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Starts the writer. [dao] is resolved on the first write, not here, so app startup does not
     * open the database.
     */
    @Synchronized
    fun initialize(dao: () -> UsageLedgerDao, scope: CoroutineScope) {
        if (queue != null) return
        val channel = Channel<Pending>(Channel.UNLIMITED)
        queue = channel
        scope.launch(Dispatchers.IO) {
            val ledger by lazy(LazyThreadSafetyMode.NONE, dao)
            for (pending in channel) {
                try {
                    ledger.add(pending.toRow(ledger))
                    _revision.value += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DebugLog.e("UsageRecorder", "Usage ledger write failed", error)
                }
            }
        }
    }

    /**
     * Records one finished Provider request. [source] null means a chat-path request whose
     * Chat/Task/Subagent attribution comes from [conversationId].
     */
    fun record(
        provider: String,
        model: String,
        usage: TokenUsage?,
        conversationId: String? = null,
        source: String? = null,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        queue?.trySend(Pending(atMillis, provider, model, source, conversationId, usage))
    }

    private suspend fun Pending.toRow(dao: UsageLedgerDao): UsageLedgerRow {
        val resolvedSource = source ?: conversationId?.let { id ->
            val kind = dao.conversationKind(id)
            when {
                kind?.taskId != null -> UsageLedgerSource.TASK
                kind?.parentConversationId != null -> UsageLedgerSource.SUBAGENT
                else -> null
            }
        } ?: UsageLedgerSource.CHAT
        val input = usage?.inputTokenCount?.toLong()
        val cached = usage?.cachedInputTokenCount?.toLong()
        return UsageLedgerRow(
            bucket = atMillis / BUCKET_MILLIS,
            provider = provider,
            model = model,
            source = resolvedSource,
            requests = 1,
            totalTokens = usage?.totalTokenCount?.toLong()?.coerceAtLeast(0) ?: 0,
            inputTokens = input ?: 0,
            cachedInputTokens = cached ?: 0,
            outputTokens = usage?.outputTokenCount?.toLong() ?: 0,
            cacheReportedInputTokens = if (input != null && cached != null) input else 0,
        )
    }

    const val BUCKET_MILLIS = 900_000L
}

/**
 * Follows one Provider request's stream for the ledger: the last usage snapshot wins, and a
 * provider retry that resets its attempt drops the failed attempt's snapshot.
 */
internal class RequestUsageTracker {
    var usage: TokenUsage? = null
        private set
    var responded = false
        private set

    fun observe(event: StreamEvent) {
        responded = true
        when (event) {
            is StreamEvent.UsageUpdate -> usage = event.usage
            is StreamEvent.Retrying -> if (event.resetCurrentAttempt) usage = null
            else -> Unit
        }
    }

    /** Records the request if the provider answered at all; a request that never connected is not counted. */
    fun record(provider: String, model: String, source: String, conversationId: String? = null) {
        if (!responded) return
        UsageRecorder.record(provider, model, usage, conversationId, source)
    }
}
