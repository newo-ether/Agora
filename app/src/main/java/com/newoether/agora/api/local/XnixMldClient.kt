package com.newoether.agora.api.local

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.newoether.agora.util.DebugLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xnix.mld.IXnixInference
import org.xnix.mld.InferenceResult

/**
 * Client connector for xnix-mld (X-NIX on-device TPU/GPU/CPU inference daemon).
 * Communicates via Binder IPC (xnix.inference) using ParcelFileDescriptor passing,
 * adhering to strict SELinux domain boundaries.
 */
class XnixMldClient {

    private var cachedService: IXnixInference? = null
    private var activeSessionId: Long? = null
    private var activeModelPath: String? = null

    /**
     * Resolves the xnix.inference system service via ServiceManager reflection.
     * Triggers on-demand daemon startup (sys.xnix.mld=1) if the service is dormant.
     */
    @Synchronized
    fun getService(): IXnixInference? {
        val existing = cachedService
        if (existing != null && (existing as? android.os.IInterface)?.asBinder()?.isBinderAlive == true) {
            return existing
        }

        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            var binder = getServiceMethod.invoke(null, SERVICE_NAME) as? IBinder

            if (binder == null) {
                // Service may be dormant; trigger on-demand startup
                try {
                    val propClass = Class.forName("android.os.SystemProperties")
                    val setMethod = propClass.getMethod("set", String::class.java, String::class.java)
                    setMethod.invoke(null, TRIGGER_PROP, "1")
                } catch (t: Throwable) {
                    DebugLog.w(TAG, "Failed to set $TRIGGER_PROP: ${t.message}")
                }

                // Poll briefly for daemon activation
                for (i in 0 until 20) {
                    Thread.sleep(100)
                    binder = getServiceMethod.invoke(null, SERVICE_NAME) as? IBinder
                    if (binder != null) break
                }
            }

            if (binder != null) {
                val svc = IXnixInference.Stub.asInterface(binder)
                cachedService = svc
                DebugLog.d(TAG, "Connected to $SERVICE_NAME successfully")
                return svc
            }
        } catch (e: Throwable) {
            DebugLog.e(TAG, "Error connecting to $SERVICE_NAME", e)
        }
        return null
    }

    fun isAvailable(): Boolean = getService() != null

    /**
     * Loads a .litertlm generative LLM/SLM using zero-copy file descriptor passing.
     */
    suspend fun loadLlm(modelFile: File, accelPref: Int = IXnixInference.ACCEL_AUTO): Long? =
        withContext(Dispatchers.IO) {
            if (!modelFile.isFile || !modelFile.canRead()) {
                DebugLog.e(TAG, "Model file not readable: ${modelFile.absolutePath}")
                return@withContext null
            }

            if (activeModelPath == modelFile.absolutePath && activeSessionId != null) {
                return@withContext activeSessionId
            }

            val svc = getService() ?: run {
                DebugLog.e(TAG, "xnix.inference service unavailable")
                return@withContext null
            }

            unload()

            try {
                ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val sess = svc.loadLlmByFd(pfd, accelPref)
                    if (sess > 0) {
                        activeSessionId = sess
                        activeModelPath = modelFile.absolutePath
                        DebugLog.d(TAG, "Loaded LLM session $sess for ${modelFile.name}")
                        return@withContext sess
                    } else {
                        DebugLog.e(TAG, "Failed to load LLM, rc=$sess")
                        return@withContext null
                    }
                }
            } catch (e: Throwable) {
                DebugLog.e(TAG, "Exception loading LLM by fd", e)
                return@withContext null
            }
        }

    /**
     * Executes generation on the active LLM session.
     */
    suspend fun generate(prompt: String, maxTokens: Int): String? =
        withContext(Dispatchers.Default) {
            val sess = activeSessionId ?: run {
                DebugLog.e(TAG, "No active LLM session to generate")
                return@withContext null
            }
            val svc = getService() ?: return@withContext null

            try {
                val output = svc.generate(sess, prompt, maxTokens)
                if (output.isNullOrEmpty()) {
                    DebugLog.w(TAG, "generate returned empty response")
                    null
                } else {
                    output
                }
            } catch (e: Throwable) {
                DebugLog.e(TAG, "Generation failed on session $sess", e)
                null
            }
        }

    /**
     * Runs classic tensor-based perception inference (.tflite).
     */
    suspend fun runPerception(session: Long, input: ByteArray): InferenceResult? =
        withContext(Dispatchers.Default) {
            val svc = getService() ?: return@withContext null
            try {
                svc.run(session, input)
            } catch (e: Throwable) {
                DebugLog.e(TAG, "runPerception failed on session $session", e)
                null
            }
        }

    fun unload() {
        val sess = activeSessionId
        if (sess != null) {
            try {
                cachedService?.unloadLlm(sess)
                DebugLog.d(TAG, "Unloaded LLM session $sess")
            } catch (t: Throwable) {
                DebugLog.w(TAG, "Error unloading session $sess: ${t.message}")
            }
            activeSessionId = null
            activeModelPath = null
        }
    }

    companion object {
        private const val TAG = "XnixMldClient"
        private const val SERVICE_NAME = "xnix.inference"
        private const val TRIGGER_PROP = "sys.xnix.mld"

        @Volatile
        private var instance: XnixMldClient? = null

        fun getInstance(): XnixMldClient =
            instance ?: synchronized(this) {
                instance ?: XnixMldClient().also { instance = it }
            }
    }
}
