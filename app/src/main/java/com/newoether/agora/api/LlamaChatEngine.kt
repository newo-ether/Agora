package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

interface NativeChatCallback {
    fun onToken(token: String)
    fun onDone()
    fun onError(message: String)
}

class ChatTemplateMessage(val role: String, val content: String)

class LlamaChatEngine(
    val modelPath: String,
    val nCtx: Int = 2048
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
    private val lock = ReentrantReadWriteLock()

    private external fun nativeChatLoadModel(path: String, nCtx: Int): Long
    private external fun nativeChatGetTemplate(handle: Long): String?
    private external fun nativeChatApplyTemplate(handle: Long, messages: Array<ChatTemplateMessage>, addAss: Boolean): String?
    private external fun nativeChatLoadMmproj(handle: Long, mmprojPath: String): Boolean
    private external fun nativeChatUnloadMmproj(handle: Long)
    private external fun nativeChatHasMmproj(handle: Long): Boolean
    private external fun nativeChatGenerateWithImages(
        handle: Long, prompt: String, imagePaths: Array<String>,
        temperature: Float, topP: Float, maxTokens: Int, callback: NativeChatCallback
    ): Int
    private external fun nativeChatGenerate(
        handle: Long, prompt: String, temperature: Float, topP: Float, maxTokens: Int,
        callback: NativeChatCallback
    ): Int
    private external fun nativeChatReset(handle: Long)
    private external fun nativeChatFreeModel(handle: Long)
    private external fun nativeChatCancel(handle: Long)

    fun isLoaded(): Boolean = nativeHandle != 0L

    fun load(): Boolean {
        if (!File(modelPath).exists()) {
            DebugLog.e(TAG, "Model file not found: $modelPath")
            return false
        }
        lock.writeLock().lock()
        try {
            nativeHandle = nativeChatLoadModel(modelPath, nCtx)
            if (nativeHandle == 0L) {
                DebugLog.e(TAG, "Failed to load model: $modelPath")
                return false
            }
            DebugLog.d(TAG, "Model loaded: $modelPath, nCtx=$nCtx")
            return true
        } finally {
            lock.writeLock().unlock()
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
        addAss: Boolean = true
    ): String? {
        lock.readLock().lock()
        try {
            if (nativeHandle == 0L) return null
            return nativeChatApplyTemplate(nativeHandle, messages.toTypedArray(), addAss)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun generate(
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 4096
    ): Flow<String> = callbackFlow {
        if (nativeHandle == 0L) {
            close(RuntimeException("Model not loaded"))
            return@callbackFlow
        }

        val callback = object : NativeChatCallback {
            override fun onToken(token: String) {
                trySend(token)
            }

            override fun onDone() {
                close()
            }

            override fun onError(message: String) {
                DebugLog.e(TAG, "Generation error: $message")
                close(RuntimeException(message))
            }
        }

        launch(Dispatchers.IO) {
            lock.readLock().lock()
            try {
                val handle = nativeHandle
                if (handle != 0L) {
                    nativeChatGenerate(handle, prompt, temperature, topP, maxTokens, callback)
                } else {
                    callback.onError("Model closed before generation started")
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
            DebugLog.e(TAG, "mmproj file not found: $mmprojPath")
            return false
        }
        lock.readLock().lock()
        try {
            if (nativeHandle == 0L) return false
            return nativeChatLoadMmproj(nativeHandle, mmprojPath)
        } finally {
            lock.readLock().unlock()
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

    fun unloadMmproj() {
        lock.readLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatUnloadMmproj(nativeHandle)
            }
        } finally {
            lock.readLock().unlock()
        }
    }

    fun generateWithImages(
        prompt: String,
        imagePaths: List<String>,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 4096
    ): Flow<String> = callbackFlow {
        if (nativeHandle == 0L) {
            close(RuntimeException("Model not loaded"))
            return@callbackFlow
        }

        val callback = object : NativeChatCallback {
            override fun onToken(token: String) { trySend(token) }
            override fun onDone() { close() }
            override fun onError(message: String) {
                DebugLog.e(TAG, "Generation error: $message")
                close(RuntimeException(message))
            }
        }

        launch(Dispatchers.IO) {
            lock.readLock().lock()
            try {
                val handle = nativeHandle
                if (handle != 0L) {
                    nativeChatGenerateWithImages(
                        handle, prompt, imagePaths.toTypedArray(),
                        temperature, topP, maxTokens, callback
                    )
                } else {
                    callback.onError("Model closed before generation started")
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

    fun resetContext() {
        lock.readLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatReset(nativeHandle)
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
                DebugLog.d(TAG, "Model closed: $modelPath")
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    protected fun finalize() {
        close()
    }
}
