package com.newoether.agora.phone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One official UserService binding; command execution is bounded and off the main thread. */
internal class PhonePrivilegedConnection(
    private val context: Context,
    private val disconnected: (Long) -> Unit = {},
) {
    private val bindingLock = Any()
    @Volatile var leaseEpoch: Long? = null
    private var pending: CompletableDeferred<IPhoneUserService>? = null
    private var connection: ServiceConnection? = null
    private val args get() = Shizuku.UserServiceArgs(ComponentName(context, PhoneUserService::class.java))
        .daemon(false).processNameSuffix("phone").tag("agora-phone").version(1)

    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    fun request() { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(7121) }
    internal suspend fun service(): IPhoneUserService {
        if (!ready()) throw PhoneFailure("permission_lost:shizuku_unavailable")
        val deferred = withContext(Dispatchers.Main.immediate) {
            synchronized(bindingLock) { pending } ?: run {
                val next = CompletableDeferred<IPhoneUserService>()
                val epoch = leaseEpoch
                val callback = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        synchronized(bindingLock) {
                            if (connection === this) next.complete(IPhoneUserService.Stub.asInterface(binder))
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        val active = synchronized(bindingLock) {
                            if (connection !== this) false else {
                                next.completeExceptionally(PhoneFailure("permission_lost:shizuku_service_disconnected"))
                                pending = null
                                connection = null
                                true
                            }
                        }
                        if (active && epoch != null) disconnected(epoch)
                    }
                }
                synchronized(bindingLock) { pending = next; connection = callback }
                try { Shizuku.bindUserService(args, callback) }
                catch (error: Exception) { close(); throw error }
                next
            }
        }
        return withTimeoutOrNull(15_000) { deferred.await() } ?: run {
            close()
            throw PhoneFailure("device_timeout", "Shizuku service binding timed out")
        }
    }
    suspend fun command(mode: String, command: String): ByteArray = withContext(Dispatchers.IO) {
        if (mode == "root") {
            val marker = "agora-root-${java.util.UUID.randomUUID()}"
            phoneProcess(listOf("su", "-c", "printf '%s\\n' ${phoneShellQuote(marker)} >&2; $command"), marker)
        } else try {
            readPhonePipe(service().command(command))
        } catch (error: android.os.DeadObjectException) {
            throw PhoneFailure("permission_lost:shizuku_binder_dead", error.message ?: "Binder died")
        } catch (error: SecurityException) {
            throw PhoneFailure("permission_lost:shizuku_security_exception", error.message ?: "Permission denied")
        }
    }
    suspend fun paste(mode: String, text: String) = withContext(Dispatchers.IO) {
        if (mode == "root") {
            val encoded = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
            command(mode, "CLASSPATH=${phoneShellQuote(context.applicationInfo.sourceDir)} app_process /system/bin " +
                "com.newoether.agora.phone.PhoneRootPaste ${phoneShellQuote(encoded)}")
        } else {
            val result = try { service().paste(text) }
            catch (error: android.os.DeadObjectException) { throw PhoneFailure("permission_lost:shizuku_binder_dead") }
            catch (error: SecurityException) { throw PhoneFailure("permission_lost:shizuku_security_exception") }
            if (result != "ok") throw PhoneFailure("clipboard_input_failed", result)
        }
    }
    fun close() {
        val previous = synchronized(bindingLock) {
            pending?.cancel()
            pending = null
            connection.also { connection = null }
        }
        if (previous != null && Shizuku.pingBinder()) runCatching { Shizuku.unbindUserService(args, previous, true) }
    }
}

/** A stalled reliable pipe closes at the deadline or cancellation, without revoking permission. */
internal suspend fun readPhonePipe(descriptor: ParcelFileDescriptor): ByteArray =
    withTimeoutOrNull(35_000) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { descriptor.close() } }
            Thread {
                try {
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 20 * 1024 * 1024) throw PhoneFailure("device_output_too_large")
                            output.write(buffer, 0, count)
                        }
                        descriptor.checkError()
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) {
                    val failure = if (error is IOException) {
                        PhoneFailure("device_command_failed", error.message ?: "Reliable pipe failed")
                    } else error
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }.apply { isDaemon = true; name = "AgoraPhone-pipe"; start() }
        }
    } ?: throw PhoneFailure("device_timeout", "Shizuku command output timed out")
