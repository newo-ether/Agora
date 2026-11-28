package com.newoether.agora.phone

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the device-local rollback transaction, independently of windows and Activity lifetime. */
internal class PhoneScreenTimeout(
    private val context: Context,
    private val journal: AtomicFile = AtomicFile(File(context.noBackupFilesDir, "phone-screen-timeout")),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Saved(val original: Int, val extended: Int)
    private val mutex = Mutex()
    private var activeEpoch: Long? = null
    private var recoveryInstalled = false

    /** Register once per process. A killed process can only restore when Android runs it again. */
    fun recoverOnAppEvents(application: Application, scope: CoroutineScope) {
        if (recoveryInstalled) return
        recoveryInstalled = true
        scope.launch { recover() }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { scope.launch { recover() } }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    suspend fun acquire(epoch: Long, isCurrent: () -> Boolean) {
        try {
            withContext(io) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) throw PhoneFailure("not_claimed")
                    if (activeEpoch == epoch) return@withLock
                    activeEpoch = null
                    if (!restoreLocked()) throw PhoneFailure("screen_timeout_restore_failed")
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) throw PhoneFailure("not_claimed")
                    if (!Settings.System.canWrite(context)) throw PhoneFailure("permission_lost:not_ready:write_settings")
                    val original = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
                    val extended = maxOf(original, MINIMUM_MS)
                    if (extended == original) {
                        activeEpoch = epoch
                        return@withLock
                    }
                    try {
                        // A durable journal must exist before the system-wide value can change.
                        save(Saved(original, extended))
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent()) throw PhoneFailure("not_claimed")
                        if (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) != original) {
                            throw PhoneFailure("screen_timeout_changed")
                        }
                        if (!Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, extended)) {
                            throw PhoneFailure("screen_timeout_write_failed")
                        }
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent()) throw PhoneFailure("not_claimed")
                        if (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) != extended) {
                            throw PhoneFailure("screen_timeout_changed")
                        }
                        activeEpoch = epoch
                    } catch (error: Exception) {
                        restoreLocked()
                        throw error
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Cancellation can also win the withContext return after the write succeeded.
            release(epoch)
            throw cancelled
        } catch (denied: SecurityException) {
            throw PhoneFailure("permission_lost:write_settings_security_exception", denied.message.orEmpty())
        } catch (error: IOException) {
            throw PhoneFailure("screen_timeout_journal_failed", error.message.orEmpty())
        }
    }

    /** Exact epochs make delayed cleanup harmless after release/reclaim. */
    suspend fun release(epoch: Long): Boolean = withContext(NonCancellable + io) {
        mutex.withLock {
            if (activeEpoch != null && activeEpoch != epoch) return@withLock true
            activeEpoch = null
            restoreLocked()
        }
    }

    /** No live lease is recreated. Failed restoration keeps the journal for a later event. */
    suspend fun recover(): Boolean = withContext(io) {
        mutex.withLock { activeEpoch != null || restoreLocked() }
    }

    private fun restoreLocked(): Boolean = try {
        val saved = read()
        if (saved == null) true else {
            val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            if (current != saved.extended) {
                // A user's later setting takes precedence, including a previous successful restore.
                clear()
                true
            } else if (!Settings.System.canWrite(context)) false else {
                if (!Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, saved.original)) false
                else {
                    val restored = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
                    if (restored == saved.extended) false else {
                        clear()
                        true
                    }
                }
            }
        }
    } catch (_: Exception) { false }

    private fun read(): Saved? {
        if (!journal.baseFile.exists() && !File("${journal.baseFile.path}.bak").exists()) return null
        return journal.openRead().use { stream ->
            val input = DataInputStream(stream)
            if (input.readInt() != MAGIC) throw IOException("Invalid screen timeout journal")
            val saved = Saved(input.readInt(), input.readInt())
            if (input.read() != -1 || saved.extended != maxOf(saved.original, MINIMUM_MS) || saved.extended == saved.original) {
                throw IOException("Invalid screen timeout journal")
            }
            saved
        }
    }

    private fun save(saved: Saved) {
        val stream = journal.startWrite()
        try {
            DataOutputStream(stream).apply {
                writeInt(MAGIC)
                writeInt(saved.original)
                writeInt(saved.extended)
                flush()
            }
            journal.finishWrite(stream)
        } catch (error: Exception) {
            journal.failWrite(stream)
            throw error
        }
        if (read() != saved) throw IOException("Screen timeout journal was not persisted")
    }

    private fun clear() {
        journal.delete()
        if (read() != null) throw IOException("Screen timeout journal was not cleared")
    }

    companion object {
        const val MINIMUM_MS = 30 * 60 * 1000
        private const val MAGIC = 0x50535431
        @Volatile private var instance: PhoneScreenTimeout? = null
        fun process(context: Context): PhoneScreenTimeout = instance ?: synchronized(this) {
            instance ?: PhoneScreenTimeout(context.applicationContext).also { instance = it }
        }
    }
}
