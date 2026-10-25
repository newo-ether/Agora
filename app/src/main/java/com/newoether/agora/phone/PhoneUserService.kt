package com.newoether.agora.phone

import android.content.ClipData
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Loaded by Shizuku under shell/root, never registered as an Android Service. */
class PhoneUserService : IPhoneUserService.Stub() {
    override fun command(command: String): ParcelFileDescriptor {
        // The image payload must never be marshalled into a Binder transaction.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        Thread {
            try {
                val bytes = phoneProcess(listOf("sh", "-c", command))
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } catch (error: Exception) {
                runCatching { pipe[1].closeWithError(error.message ?: "device_command_failed") }
            }
        }.apply { isDaemon = true; start() }
        return pipe[0]
    }
    override fun paste(text: String): String = try {
        privilegedPaste(text)
        "ok"
    } catch (error: Exception) { "${error.javaClass.simpleName}: ${error.message}" }
    override fun destroy() { kotlin.system.exitProcess(0) }
}

internal fun phoneProcess(arguments: List<String>): ByteArray {
    val process = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.PIPE).start()
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
    fun drain(input: java.io.InputStream, target: ByteArrayOutputStream) = Thread {
        input.use { stream ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = stream.read(bytes)
                if (count < 0) break
                if (target.size() + count <= 20 * 1024 * 1024) target.write(bytes, 0, count)
                else overflow.set(true)
            }
        }
    }.apply { isDaemon = true; start() }
    val stdout = drain(process.inputStream, output)
    val stderr = drain(process.errorStream, error)
    try {
        if (!process.waitFor(30, TimeUnit.SECONDS)) throw PhoneFailure("device_timeout")
        stdout.join(2000); stderr.join(2000)
        if (stdout.isAlive || stderr.isAlive) throw PhoneFailure("device_output_incomplete")
        if (overflow.get()) throw PhoneFailure("device_output_too_large")
        if (process.exitValue() != 0) throw PhoneFailure("device_command_failed", error.toString("UTF-8").take(1000))
        return output.toByteArray()
    } finally { process.destroyForcibly() }
}

/** Hidden clipboard API is used only inside a privileged app_process/UserService process. */
private fun privilegedPaste(text: String) {
    val manager = Class.forName("android.os.ServiceManager")
    val binder = manager.getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
    val stub = Class.forName("android.content.IClipboard\$Stub")
    val clipboard = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    val methods = Class.forName("android.content.IClipboard").methods
    val get = methods.filter { it.name == "getPrimaryClip" }.maxBy { it.parameterCount }
    val set = methods.filter { it.name == "setPrimaryClip" }.maxBy { it.parameterCount }
    val user = phoneProcess(listOf("am", "get-current-user")).toString(Charsets.UTF_8).trim().toInt()
    fun arguments(types: Array<Class<*>>, clip: ClipData? = null): Array<Any?> {
        var strings = 0
        var integers = 0
        return types.map { type -> when (type) {
            ClipData::class.java -> clip
            String::class.java -> if (strings++ == 0) "com.android.shell" else null
            Integer.TYPE -> if (integers++ == 0) user else 0
            else -> throw PhoneFailure("clipboard_api_unsupported")
        } }.toTypedArray()
    }
    val previous = get.invoke(clipboard, *arguments(get.parameterTypes)) as? ClipData
    try {
        set.invoke(clipboard, *arguments(set.parameterTypes, ClipData.newPlainText("Agora", text)))
        phoneProcess(listOf("input", "keyevent", "279"))
        // The focused app handles the key asynchronously and reads the clip after dispatch returns.
        Thread.sleep(300)
    } finally {
        if (previous != null) set.invoke(clipboard, *arguments(set.parameterTypes, previous))
        else {
            val clear = methods.filter { it.name == "clearPrimaryClip" }.maxByOrNull { it.parameterCount }
            if (clear != null) clear.invoke(clipboard, *arguments(clear.parameterTypes))
            else set.invoke(clipboard, *arguments(set.parameterTypes, ClipData.newPlainText("", "")))
        }
    }
}

/** Root runs this entry from the installed APK rather than granting the app hidden API access. */
object PhoneRootPaste {
    @JvmStatic fun main(arguments: Array<String>) {
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare()
        try {
            require(arguments.size == 1)
            privilegedPaste(String(android.util.Base64.decode(arguments[0], android.util.Base64.NO_WRAP), Charsets.UTF_8))
        } catch (error: Throwable) {
            System.err.println("${error.javaClass.simpleName}: ${error.message}")
            kotlin.system.exitProcess(1)
        }
    }
}
