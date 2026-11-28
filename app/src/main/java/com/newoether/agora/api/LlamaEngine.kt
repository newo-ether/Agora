package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import java.io.File

object LlamaEngine {
    private const val TAG = "LlamaEngine"

    private var nativeHandle: Long = 0L

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("agora_llama")
    }

    private external fun nativeInitializeBackends(nativeLibraryDir: String): Boolean
    private external fun nativeLoadModel(path: String): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeComputeEmbedding(handle: Long, text: String): FloatArray?
    private external fun nativeGetEmbeddingDim(handle: Long): Int

    internal fun initializeBackends(nativeLibraryDir: String): Boolean =
        nativeInitializeBackends(nativeLibraryDir)

    fun isModelReady(modelPath: String): Boolean {
        return modelPath.isNotBlank() && File(modelPath).exists() && File(modelPath).length() > 0
    }

    suspend fun computeEmbedding(text: String, modelPath: String): FloatArray? {
        val results = computeEmbeddings(listOf(text), modelPath)
        return results.firstOrNull()
    }

    suspend fun computeEmbeddings(texts: List<String>, modelPath: String): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        val start = System.currentTimeMillis()
        return LocalModelRuntime.runEmbedding(modelPath) {
            texts.mapIndexed { i, text ->
                try {
                    val embd = nativeComputeEmbedding(nativeHandle, text)
                    if (embd == null) {
                        DebugLog.e(TAG, "nativeComputeEmbedding returned null for text len=${text.length} (${i + 1}/${texts.size})")
                    }
                    embd
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Embedding computation crashed for text ${i + 1}/${texts.size}", e)
                    null
                }
            }
        }?.also {
            DebugLog.d(TAG, "Batch complete: ${texts.size} texts in ${System.currentTimeMillis() - start}ms")
        } ?: texts.map { null }
    }

    internal fun loadResident(modelPath: String): Boolean {
        check(nativeHandle == 0L) { "Embedding model already resident" }
        val start = System.currentTimeMillis()
        nativeHandle = nativeLoadModel(modelPath)
        if (nativeHandle == 0L) {
            DebugLog.e(TAG, "Failed to load model (${System.currentTimeMillis() - start}ms)")
            return false
        }
        DebugLog.d(
            TAG,
            "Model loaded in ${System.currentTimeMillis() - start}ms, dim=${nativeGetEmbeddingDim(nativeHandle)}",
        )
        return true
    }

    internal fun unloadResident() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) nativeFreeModel(handle)
    }
}
