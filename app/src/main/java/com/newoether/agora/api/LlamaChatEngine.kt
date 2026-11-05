package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

internal enum class LlamaGenerationStopReason(val nativeValue: String) {
    EOG("eog"),
    MAX_TOKENS("max_tokens"),
    CONTEXT_FULL("context_full"),
    CANCELLED("cancelled");

    companion object {
        fun fromNative(value: String): LlamaGenerationStopReason? = entries
            .firstOrNull { it.nativeValue == value }
    }
}

internal data class LlamaToolCall(
    val index: Int,
    val id: String?,
    val name: String,
    val arguments: String,
)

internal sealed interface LlamaGenerationEvent {
    data class Text(val value: String) : LlamaGenerationEvent
    data class Thought(val value: String) : LlamaGenerationEvent
    data class ToolCallUpdate(val call: LlamaToolCall) : LlamaGenerationEvent
    data class ToolCallsCompleted(val calls: List<LlamaToolCall>) : LlamaGenerationEvent
    data class Completed(
        val reason: LlamaGenerationStopReason,
        val inputTokenCount: Int,
        val outputTokenCount: Int,
    ) : LlamaGenerationEvent

    data class Failed(
        val message: String,
        val inputTokenCount: Int,
        val outputTokenCount: Int,
    ) : LlamaGenerationEvent
}

interface NativeChatCallback {
    fun onText(text: String): Boolean
    fun onThought(thought: String): Boolean
    fun onToolCall(index: Int, id: String, name: String, arguments: String): Boolean
    fun onToolCallsComplete(): Boolean
    fun onDone(reason: String, inputTokenCount: Int, outputTokenCount: Int)
    fun onError(message: String, inputTokenCount: Int, outputTokenCount: Int)
}

class ChatTemplateToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

class ChatTemplateMessage(
    val role: String,
    val content: String,
    val toolCalls: Array<ChatTemplateToolCall> = emptyArray(),
    val toolName: String = "",
    val toolCallId: String = "",
)

class ChatTemplateTool(
    val name: String,
    val description: String,
    val parameters: String,
)

class LlamaChatTemplateRequest(
    val messages: Array<ChatTemplateMessage>,
    val tools: Array<ChatTemplateTool>,
    val addGenerationPrompt: Boolean,
    val enableThinking: Boolean,
)

class ChatTemplateGrammarTrigger(
    val type: Int,
    val value: String,
    val token: Int,
)

class LlamaChatTemplateResult(
    val prompt: String,
    val supportsTools: Boolean,
    val grammar: String = "",
    val grammarLazy: Boolean = false,
    val generationPrompt: String = "",
    val grammarTriggers: Array<ChatTemplateGrammarTrigger> = emptyArray(),
    val preservedTokens: Array<String> = emptyArray(),
    val format: Int = 0,
    val parser: String = "",
)

/** RAM limits for prompt checkpoints, scaled to the device by the caller. */
data class MemoryCheckpointBudget(val perCheckpointBytes: Long, val totalBytes: Long)

class LlamaChatEngine(
    val modelPath: String,
    val nCtx: Int = 2048,
    /** Where prompt-prefix snapshots are kept; blank disables them. */
    private val prefixCacheDir: String = "",
    /** Disk budget for those snapshots; 0 keeps the native default. */
    private val prefixCacheMaxBytes: Long = 0,
    /** RAM budget for prompt checkpoints; null keeps the native defaults. */
    private val memoryCheckpointBudget: MemoryCheckpointBudget? = null,
) : Closeable {
    companion object {
        private const val TAG = "LlamaChatEngine"

        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("agora_llama")
        }
    }

    @Volatile
    private var nativeHandle: Long = 0
    @Volatile
    private var loadedMmprojPath: String? = null
    private val lock = ReentrantReadWriteLock()

    private external fun nativeChatLoadModel(path: String, nCtx: Int): Long
    private external fun nativeChatGetTemplate(handle: Long): String?
    private external fun nativeChatApplyTemplate(
        handle: Long,
        request: LlamaChatTemplateRequest,
    ): LlamaChatTemplateResult?
    private external fun nativeChatLoadMmproj(handle: Long, mmprojPath: String): Boolean
    private external fun nativeChatUnloadMmproj(handle: Long)
    private external fun nativeChatHasMmproj(handle: Long): Boolean
    private external fun nativeChatGenerateWithImages(
        handle: Long, template: LlamaChatTemplateResult, imagePaths: Array<String>,
        temperature: Float, topP: Float, frequencyPenalty: Float, presencePenalty: Float,
        maxTokens: Int, callback: NativeChatCallback,
    ): Int
    private external fun nativeChatGenerate(
        handle: Long, template: LlamaChatTemplateResult, temperature: Float, topP: Float,
        frequencyPenalty: Float, presencePenalty: Float, maxTokens: Int,
        callback: NativeChatCallback,
    ): Int
    private external fun nativeChatCountTokens(handle: Long, text: String): Int
    private external fun nativeChatFreeModel(handle: Long)
    private external fun nativeChatCancel(handle: Long)
    private external fun nativeChatSetPrefixCacheDir(handle: Long, directory: String, maxBytes: Long)
    private external fun nativeChatSetMemoryCheckpointBudget(handle: Long, perCheckpoint: Long, total: Long)
    private external fun nativeChatDropMemoryCheckpoints(handle: Long)

    fun isLoaded(): Boolean = nativeHandle != 0L

    fun matches(path: String, contextSize: Int): Boolean =
        nativeHandle != 0L && modelPath == path && nCtx == contextSize

    fun load(): Boolean {
        if (!File(modelPath).exists()) {
            DebugLog.e(TAG, "Model file not found")
            return false
        }
        lock.writeLock().lock()
        try {
            nativeHandle = nativeChatLoadModel(modelPath, nCtx)
            if (nativeHandle == 0L) {
                DebugLog.e(TAG, "Failed to load model")
                return false
            }
            if (prefixCacheDir.isNotBlank()) {
                nativeChatSetPrefixCacheDir(nativeHandle, prefixCacheDir, prefixCacheMaxBytes)
            }
            memoryCheckpointBudget?.let {
                nativeChatSetMemoryCheckpointBudget(nativeHandle, it.perCheckpointBytes, it.totalBytes)
            }
            DebugLog.d(TAG, "Model loaded, nCtx=$nCtx")
            return true
        } finally {
            lock.writeLock().unlock()
        }
    }

    /**
     * Exact token count of [text] under this model's vocabulary, or null when no model is loaded.
     *
     * Counting only reads the vocabulary, so it shares the read lock with generation and cannot run
     * while the model is being freed. Content is counted without special tokens; role and template
     * markers are framing and are accounted for separately.
     */
    fun countTokensOrNull(text: String): Int? {
        if (text.isEmpty()) return 0
        lock.readLock().lock()
        try {
            if (nativeHandle == 0L) return null
            return nativeChatCountTokens(nativeHandle, text).takeIf { it >= 0 }
        } catch (_: UnsatisfiedLinkError) {
            return null
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getChatTemplate(): String? {
        lock.readLock().lock()
        try {
            if (nativeHandle == 0L) return null
            return nativeChatGetTemplate(nativeHandle)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun applyTemplate(
        messages: List<ChatTemplateMessage>,
        tools: List<ChatTemplateTool> = emptyList(),
        addAss: Boolean = true,
        enableThinking: Boolean = true,
    ): LlamaChatTemplateResult? {
        lock.readLock().lock()
        try {
            if (nativeHandle == 0L) return null
            return nativeChatApplyTemplate(
                nativeHandle,
                LlamaChatTemplateRequest(
                    messages = messages.toTypedArray(),
                    tools = tools.toTypedArray(),
                    addGenerationPrompt = addAss,
                    enableThinking = enableThinking,
                ),
            )
        } finally {
            lock.readLock().unlock()
        }
    }

    internal fun generate(
        template: LlamaChatTemplateResult,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        frequencyPenalty: Float = 0f,
        presencePenalty: Float = 0f,
        maxTokens: Int = 4096,
    ): Flow<LlamaGenerationEvent> = callbackFlow {
        if (nativeHandle == 0L) {
            close(RuntimeException("Model not loaded"))
            return@callbackFlow
        }

        val terminalSignalled = AtomicBoolean(false)
        val toolCalls = linkedMapOf<Int, LlamaToolCall>()
        val callback = object : NativeChatCallback {
            override fun onText(text: String): Boolean =
                !terminalSignalled.get() && text.isNotEmpty() &&
                    trySendBlocking(LlamaGenerationEvent.Text(text)).isSuccess

            override fun onThought(thought: String): Boolean =
                !terminalSignalled.get() && thought.isNotEmpty() &&
                    trySendBlocking(LlamaGenerationEvent.Thought(thought)).isSuccess

            override fun onToolCall(
                index: Int,
                id: String,
                name: String,
                arguments: String,
            ): Boolean {
                if (terminalSignalled.get() || index < 0) return false
                val previous = toolCalls[index]
                val call = LlamaToolCall(
                    index = index,
                    id = id.takeIf(String::isNotBlank) ?: previous?.id,
                    name = name.takeIf(String::isNotBlank) ?: previous?.name.orEmpty(),
                    arguments = arguments.takeIf(String::isNotEmpty)
                        ?: previous?.arguments.orEmpty(),
                )
                toolCalls[index] = call
                return trySendBlocking(LlamaGenerationEvent.ToolCallUpdate(call)).isSuccess
            }

            override fun onToolCallsComplete(): Boolean {
                if (terminalSignalled.get()) return false
                if (toolCalls.isEmpty()) return true
                return trySendBlocking(
                    LlamaGenerationEvent.ToolCallsCompleted(toolCalls.toSortedMap().values.toList())
                ).isSuccess
            }

            override fun onDone(reason: String, inputTokenCount: Int, outputTokenCount: Int) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                val parsedReason = LlamaGenerationStopReason.fromNative(reason)
                if (parsedReason == null) {
                    trySendBlocking(
                        LlamaGenerationEvent.Failed(
                            message = "Unknown native stop reason: $reason",
                            inputTokenCount = inputTokenCount,
                            outputTokenCount = outputTokenCount,
                        )
                    )
                } else {
                    trySendBlocking(
                        LlamaGenerationEvent.Completed(
                            reason = parsedReason,
                            inputTokenCount = inputTokenCount,
                            outputTokenCount = outputTokenCount,
                        )
                    )
                }
                this@callbackFlow.close()
            }

            override fun onError(message: String, inputTokenCount: Int, outputTokenCount: Int) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                DebugLog.e(TAG, "Generation error reported by native backend")
                trySendBlocking(
                    LlamaGenerationEvent.Failed(message, inputTokenCount, outputTokenCount)
                )
                this@callbackFlow.close()
            }
        }

        launch(Dispatchers.IO) {
            lock.readLock().lock()
            try {
                val handle = nativeHandle
                if (handle != 0L) {
                    val result = nativeChatGenerate(
                        handle, template, temperature, topP, frequencyPenalty, presencePenalty,
                        maxTokens, callback,
                    )
                    if (result < 0 && !terminalSignalled.get()) {
                        callback.onError("Native generation ended without a terminal result", 0, 0)
                    }
                } else {
                    callback.onError("Model closed before generation started", 0, 0)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "nativeChatGenerate crashed", e)
                close(e)
            } finally {
                lock.readLock().unlock()
            }
        }

        awaitClose {
            lock.readLock().lock()
            try {
                if (nativeHandle != 0L) {
                    nativeChatCancel(nativeHandle)
                }
            } finally {
                lock.readLock().unlock()
            }
        }
    }

    fun loadMmproj(mmprojPath: String): Boolean {
        if (!File(mmprojPath).exists()) {
            DebugLog.e(TAG, "mmproj file not found")
            return false
        }
        lock.writeLock().lock()
        try {
            if (nativeHandle == 0L) return false
            if (loadedMmprojPath == mmprojPath && nativeChatHasMmproj(nativeHandle)) {
                return true
            }
            val loaded = nativeChatLoadMmproj(nativeHandle, mmprojPath)
            if (loaded) loadedMmprojPath = mmprojPath
            return loaded
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun hasMmproj(): Boolean {
        lock.readLock().lock()
        try {
            return nativeHandle != 0L && nativeChatHasMmproj(nativeHandle)
        } finally {
            lock.readLock().unlock()
        }
    }

    /**
     * Frees the RAM prompt checkpoints (disk snapshots stay). Skips when a generation holds the
     * engine, so callers on the main thread never wait.
     */
    fun dropMemoryCheckpoints(): Boolean {
        if (!lock.writeLock().tryLock()) return false
        try {
            if (nativeHandle != 0L) nativeChatDropMemoryCheckpoints(nativeHandle)
            return true
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun unloadMmproj() {
        lock.writeLock().lock()
        try {
            if (nativeHandle != 0L && loadedMmprojPath != null) {
                nativeChatUnloadMmproj(nativeHandle)
            }
            loadedMmprojPath = null
        } finally {
            lock.writeLock().unlock()
        }
    }

    internal fun generateWithImages(
        template: LlamaChatTemplateResult,
        imagePaths: List<String>,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        frequencyPenalty: Float = 0f,
        presencePenalty: Float = 0f,
        maxTokens: Int = 4096,
    ): Flow<LlamaGenerationEvent> = callbackFlow {
        if (nativeHandle == 0L) {
            close(RuntimeException("Model not loaded"))
            return@callbackFlow
        }

        val terminalSignalled = AtomicBoolean(false)
        val toolCalls = linkedMapOf<Int, LlamaToolCall>()
        val callback = object : NativeChatCallback {
            override fun onText(text: String): Boolean =
                !terminalSignalled.get() && text.isNotEmpty() &&
                    trySendBlocking(LlamaGenerationEvent.Text(text)).isSuccess

            override fun onThought(thought: String): Boolean =
                !terminalSignalled.get() && thought.isNotEmpty() &&
                    trySendBlocking(LlamaGenerationEvent.Thought(thought)).isSuccess

            override fun onToolCall(
                index: Int,
                id: String,
                name: String,
                arguments: String,
            ): Boolean {
                if (terminalSignalled.get() || index < 0) return false
                val previous = toolCalls[index]
                val call = LlamaToolCall(
                    index = index,
                    id = id.takeIf(String::isNotBlank) ?: previous?.id,
                    name = name.takeIf(String::isNotBlank) ?: previous?.name.orEmpty(),
                    arguments = arguments.takeIf(String::isNotEmpty)
                        ?: previous?.arguments.orEmpty(),
                )
                toolCalls[index] = call
                return trySendBlocking(LlamaGenerationEvent.ToolCallUpdate(call)).isSuccess
            }

            override fun onToolCallsComplete(): Boolean {
                if (terminalSignalled.get()) return false
                if (toolCalls.isEmpty()) return true
                return trySendBlocking(
                    LlamaGenerationEvent.ToolCallsCompleted(toolCalls.toSortedMap().values.toList())
                ).isSuccess
            }

            override fun onDone(reason: String, inputTokenCount: Int, outputTokenCount: Int) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                val parsedReason = LlamaGenerationStopReason.fromNative(reason)
                val event = if (parsedReason == null) {
                    LlamaGenerationEvent.Failed(
                        message = "Unknown native stop reason: $reason",
                        inputTokenCount = inputTokenCount,
                        outputTokenCount = outputTokenCount,
                    )
                } else {
                    LlamaGenerationEvent.Completed(
                        reason = parsedReason,
                        inputTokenCount = inputTokenCount,
                        outputTokenCount = outputTokenCount,
                    )
                }
                trySendBlocking(event)
                this@callbackFlow.close()
            }

            override fun onError(message: String, inputTokenCount: Int, outputTokenCount: Int) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                DebugLog.e(TAG, "Generation error reported by native backend")
                trySendBlocking(
                    LlamaGenerationEvent.Failed(message, inputTokenCount, outputTokenCount)
                )
                this@callbackFlow.close()
            }
        }

        launch(Dispatchers.IO) {
            lock.readLock().lock()
            try {
                val handle = nativeHandle
                if (handle != 0L) {
                    val result = nativeChatGenerateWithImages(
                        handle, template, imagePaths.toTypedArray(),
                        temperature, topP, frequencyPenalty, presencePenalty,
                        maxTokens, callback,
                    )
                    if (result < 0 && !terminalSignalled.get()) {
                        callback.onError("Native generation ended without a terminal result", 0, 0)
                    }
                } else {
                    callback.onError("Model closed before generation started", 0, 0)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "nativeChatGenerateWithImages crashed", e)
                close(e)
            } finally {
                lock.readLock().unlock()
            }
        }

        awaitClose {
            lock.readLock().lock()
            try {
                if (nativeHandle != 0L) nativeChatCancel(nativeHandle)
            } finally {
                lock.readLock().unlock()
            }
        }
    }

    fun cancel() {
        lock.readLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatCancel(nativeHandle)
            }
        } finally {
            lock.readLock().unlock()
        }
    }

    override fun close() {
        lock.readLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatCancel(nativeHandle)
            }
        } finally {
            lock.readLock().unlock()
        }

        lock.writeLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatFreeModel(nativeHandle)
                nativeHandle = 0L
                loadedMmprojPath = null
                DebugLog.d(TAG, "Model closed")
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    protected fun finalize() {
        close()
    }
}
