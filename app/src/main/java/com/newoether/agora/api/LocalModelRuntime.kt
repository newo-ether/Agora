package com.newoether.agora.api

import com.newoether.agora.data.DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES
import com.newoether.agora.data.normalizeLocalModelIdleRetentionMinutes
import com.newoether.agora.util.DebugLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal sealed interface LocalModelIdentity {
    val canonicalPath: String

    data class Chat(
        override val canonicalPath: String,
        val nCtx: Int,
    ) : LocalModelIdentity

    data class Embedding(
        override val canonicalPath: String,
    ) : LocalModelIdentity
}

/** Fair process-wide admission gate for every embedded llama.cpp operation. */
internal class LocalModelTaskQueue(
    private val onTaskArrived: () -> Unit = {},
    private val onQueueIdle: () -> Unit = {},
) {
    private val permit = Semaphore(1)
    private val stateLock = Any()
    private var submittedTasks = 0

    suspend fun <T> run(block: suspend () -> T): T {
        synchronized(stateLock) {
            submittedTasks++
            onTaskArrived()
        }
        try {
            return permit.withPermit { block() }
        } finally {
            synchronized(stateLock) {
                submittedTasks--
                if (submittedTasks == 0) onQueueIdle()
            }
        }
    }

    fun signalIdleIfEmpty(): Boolean = synchronized(stateLock) {
        if (submittedTasks != 0) return@synchronized false
        onQueueIdle()
        true
    }

    suspend fun runIfIdle(block: () -> Unit): Boolean = permit.withPermit {
        synchronized(stateLock) {
            if (submittedTasks != 0) return@synchronized false
            block()
            true
        }
    }
}

/**
 * Canonical owner of the one llama.cpp model/context that may be resident in this process.
 *
 * A complete Chat request or Embedding batch holds [tasks] until its native work and cleanup have
 * finished. Kotlin's coroutine Semaphore is FIFO, so remote work stays parallel while every local
 * waiter observes one strict order. Resident identity changes always close the old native owner
 * before attempting the new load; a failed replacement therefore leaves no model resident.
 */
internal object LocalModelRuntime {
    private const val TAG = "LocalModelRuntime"
    private const val MILLIS_PER_MINUTE = 60_000L

    private sealed interface Resident {
        val identity: LocalModelIdentity

        data class Chat(
            override val identity: LocalModelIdentity.Chat,
            val engine: LlamaChatEngine,
        ) : Resident

        data class Embedding(
            override val identity: LocalModelIdentity.Embedding,
        ) : Resident
    }

    private val lifecycleLock = Any()
    private val tasks = LocalModelTaskQueue(
        onTaskArrived = ::cancelIdleDeadline,
        onQueueIdle = ::startIdleDeadline,
    )
    // Volatile because token counting reads the resident model from arbitrary threads, outside the
    // task queue that loads and unloads it.
    @Volatile
    private var resident: Resident? = null
    private var idleScope: CoroutineScope? = null
    private var idleBindingJob: Job? = null
    private var idleDeadlineJob: Job? = null
    private var idleEpoch = 0L
    private var idleRetentionMinutes = DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES

    @Volatile
    private var nativeBackendDirectory: String? = null

    @Volatile
    private var activeChatEngine: LlamaChatEngine? = null

    @Volatile
    private var prefixCacheDirectory: String = ""

    @Volatile
    private var prefixCacheMaxBytes: Long = 0

    @Volatile
    private var memoryCheckpointBudget: MemoryCheckpointBudget? = null

    /** RAM limits for prompt checkpoints in chat models loaded from now on. */
    internal fun setMemoryCheckpointBudget(budget: MemoryCheckpointBudget) {
        memoryCheckpointBudget = budget
    }

    /** Frees the resident chat model's RAM prompt checkpoints, e.g. on onTrimMemory. */
    internal fun dropMemoryCheckpoints() {
        (resident as? Resident.Chat)?.engine?.dropMemoryCheckpoints()
    }

    /**
     * Enables persistent prompt-prefix snapshots for chat models loaded from now on. The
     * directory belongs under the app cache so Android's Clear cache and low-storage cleanup
     * remove it; [maxBytes] keeps it inside the app's cache quota.
     */
    internal fun setPrefixCacheDirectory(directory: String, maxBytes: Long) {
        prefixCacheDirectory = directory
        prefixCacheMaxBytes = maxBytes
    }

    internal fun initialize(nativeLibraryDir: String) {
        require(nativeLibraryDir.isNotBlank()) { "Native library directory must not be blank" }
        val canonicalDirectory = canonicalize(nativeLibraryDir)
        synchronized(lifecycleLock) {
            val currentDirectory = nativeBackendDirectory
            if (currentDirectory != null) {
                check(currentDirectory == canonicalDirectory) {
                    "Local llama backends already initialized from a different directory"
                }
                return
            }
            if (!LlamaEngine.initializeBackends(canonicalDirectory)) {
                DebugLog.e(TAG, "Unable to initialize the Local llama CPU backend")
                return
            }
            nativeBackendDirectory = canonicalDirectory
        }
    }

    suspend fun runChat(
        modelPath: String,
        nCtx: Int,
        block: suspend (LlamaChatEngine) -> Unit,
    ): Boolean = tasks.run {
        if (nativeBackendDirectory == null) return@run false
        val identity = LocalModelIdentity.Chat(canonicalize(modelPath), nCtx)
        val current = resident
        val engine = if (current is Resident.Chat && current.identity == identity) {
            current.engine
        } else {
            unloadResident()
            val loaded = LlamaChatEngine(
                identity.canonicalPath, identity.nCtx, prefixCacheDirectory, prefixCacheMaxBytes,
                memoryCheckpointBudget,
            )
            if (!loaded.load()) {
                loaded.close()
                return@run false
            }
            resident = Resident.Chat(identity, loaded)
            loaded
        }

        activeChatEngine = engine
        try {
            block(engine)
            true
        } finally {
            activeChatEngine = null
        }
    }

    suspend fun <T> runEmbedding(
        modelPath: String,
        block: () -> T,
    ): T? = tasks.run {
        if (nativeBackendDirectory == null) return@run null
        val identity = LocalModelIdentity.Embedding(canonicalize(modelPath))
        if (resident?.identity != identity) {
            unloadResident()
            if (!LlamaEngine.loadResident(identity.canonicalPath)) return@run null
            resident = Resident.Embedding(identity)
        }
        block()
    }

    fun cancelActiveChat() {
        activeChatEngine?.cancel()
    }

    /**
     * Exact token count from the resident chat model, or null when no chat model is loaded.
     *
     * Used by the context estimate, which must stay synchronous, so this neither loads a model nor
     * waits for the task queue. A model that is being freed reports null through the engine's own
     * lock, and the caller falls back to its offline counter.
     */
    fun exactTokenCountOrNull(text: String): Int? =
        (resident as? Resident.Chat)?.engine?.countTokensOrNull(text)

    fun bindIdleRetention(
        retentionMinutes: StateFlow<Int>,
        scope: CoroutineScope,
    ) {
        synchronized(lifecycleLock) {
            if (idleBindingJob != null) return
            idleScope = scope
            idleBindingJob = scope.launch {
                retentionMinutes.collect(::updateIdleRetention)
            }
        }
    }

    private fun updateIdleRetention(minutes: Int) {
        synchronized(lifecycleLock) {
            idleRetentionMinutes = normalizeLocalModelIdleRetentionMinutes(minutes)
            invalidateIdleDeadlineLocked()
        }
        tasks.signalIdleIfEmpty()
    }

    private fun cancelIdleDeadline() {
        synchronized(lifecycleLock) {
            invalidateIdleDeadlineLocked()
        }
    }

    private fun startIdleDeadline() {
        synchronized(lifecycleLock) {
            val scope = idleScope ?: return
            invalidateIdleDeadlineLocked()
            val epoch = idleEpoch
            val delayMillis = idleRetentionMinutes * MILLIS_PER_MINUTE
            idleDeadlineJob = scope.launch {
                if (delayMillis > 0) delay(delayMillis)
                tasks.runIfIdle {
                    synchronized(lifecycleLock) {
                        if (epoch != idleEpoch) return@runIfIdle
                        idleDeadlineJob = null
                        unloadResident()
                    }
                }
            }
        }
    }

    private fun invalidateIdleDeadlineLocked() {
        idleEpoch++
        idleDeadlineJob?.cancel()
        idleDeadlineJob = null
    }

    private fun unloadResident() {
        val description = when (val current = resident ?: return) {
            is Resident.Chat -> {
                current.engine.close()
                "Chat"
            }
            is Resident.Embedding -> {
                LlamaEngine.unloadResident()
                "Embedding"
            }
        }
        resident = null
        DebugLog.d(TAG, "Unloaded resident $description")
    }

    private fun canonicalize(path: String): String {
        val file = File(path)
        return runCatching(file::getCanonicalPath).getOrElse { file.absolutePath }
    }
}
