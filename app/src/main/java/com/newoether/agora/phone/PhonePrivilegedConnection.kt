package com.newoether.agora.phone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** One official UserService binding; command execution is bounded and off the main thread. */
internal class PhonePrivilegedConnection(private val context: Context) {
    private val bindingLock = Any()
    private var pending: CompletableDeferred<IPhoneUserService>? = null
    private val args get() = Shizuku.UserServiceArgs(ComponentName(context, PhoneUserService::class.java))
        .daemon(false).processNameSuffix("phone").tag("agora-phone").version(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IPhoneUserService.Stub.asInterface(binder)
            synchronized(bindingLock) { pending?.complete(service) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindingLock) {
                pending?.completeExceptionally(PhoneFailure("permission_lost")); pending = null
            }
        }
    }
    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    fun request() { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(7121) }
    private suspend fun service(): IPhoneUserService {
        if (!ready()) throw PhoneFailure("permission_lost")
        val deferred = synchronized(bindingLock) {
            pending ?: CompletableDeferred<IPhoneUserService>().also { pending = it }
        }
        if (!deferred.isCompleted) withContext(Dispatchers.Main.immediate) {
            Shizuku.bindUserService(args, connection)
        }
        return withTimeout(15_000) { deferred.await() }
    }
    suspend fun command(mode: String, command: String): ByteArray = withContext(Dispatchers.IO) {
        if (mode == "root") phoneProcess(listOf("su", "-c", command)) else {
            val descriptor = service().command(command)
            try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
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
            } catch (error: IOException) {
                // Reliable-pipe failures carry the privileged process's bounded diagnostics.
                throw PhoneFailure("device_command_failed", error.message ?: "device_command_failed")
            }
        }
    }
    suspend fun paste(mode: String, text: String) = withContext(Dispatchers.IO) {
        if (mode == "root") {
            val encoded = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
            command(mode, "CLASSPATH=${phoneShellQuote(context.applicationInfo.sourceDir)} app_process /system/bin " +
                "com.newoether.agora.phone.PhoneRootPaste ${phoneShellQuote(encoded)}")
        } else {
            val result = service().paste(text)
            if (result != "ok") throw PhoneFailure("clipboard_input_failed", result)
        }
    }
    fun close() {
        synchronized(bindingLock) { pending?.cancel(); pending = null }
        if (Shizuku.pingBinder()) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }
}
