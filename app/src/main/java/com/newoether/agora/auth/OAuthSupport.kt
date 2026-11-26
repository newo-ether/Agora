package com.newoether.agora.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.ServerSocketChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A sign-in failure whose message is safe to show the user. */
open class OAuthException(message: String) : IOException(message)

/** Observable account state for the settings page. */
data class OAuthAccountState(
    val signedIn: Boolean = false,
    /** Email or display name of the signed-in account. */
    val account: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** A browser sign-in account as the settings page sees it. */
interface OAuthAccount {
    val state: kotlinx.coroutines.flow.StateFlow<OAuthAccountState>
    suspend fun signIn()
    suspend fun signOut()
}

internal object Pkce {
    private val random = SecureRandom()

    fun randomToken(bytes: Int = 32): String = base64Url(ByteArray(bytes).also(random::nextBytes))

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * One encrypted credential record in no-backup storage: tokens never travel in backups or
 * cloud restore, and a restored device simply signs in again.
 */
internal class OAuthCredentialFile(context: Context, name: String) {
    private val file = File(File(context.noBackupFilesDir, "oauth"), "$name.json")

    fun read(): String? = runCatching {
        file.takeIf(File::isFile)?.readText()?.let(SecretCrypto::decrypt)?.takeIf(String::isNotBlank)
    }.getOrNull()

    fun write(plaintext: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(SecretCrypto.encrypt(plaintext))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Could not save credentials")
        }
    }

    fun delete() {
        file.delete()
    }
}

/** What the loopback listener sends back to the browser tab. */
internal sealed interface CallbackReply {
    data class Page(val title: String, val message: String) : CallbackReply
    data class Redirect(val url: String) : CallbackReply
}

/**
 * Minimal HTTP listener on 127.0.0.1 for one OAuth redirect. Only [path] is answered; anything
 * else (favicon probes) gets 404 and the listener keeps waiting. Cancelling the caller closes it.
 */
internal class LoopbackCallbackServer private constructor(private val channel: ServerSocketChannel) : Closeable {
    val port: Int get() = channel.socket().localPort

    suspend fun <T> await(
        path: String,
        respond: suspend (Map<String, String>) -> Pair<T, CallbackReply>,
    ): T = withContext(Dispatchers.IO) {
        var result: Box<T>? = null
        while (result == null) {
            // A channel accept is interruptible, so cancelling the caller unblocks it.
            val connection = runInterruptible { channel.accept() }.socket()
            result = connection.use { handle(it, path, respond) }
        }
        checkNotNull(result).value
    }

    private class Box<T>(val value: T)

    private suspend fun <T> handle(
        connection: Socket,
        path: String,
        respond: suspend (Map<String, String>) -> Pair<T, CallbackReply>,
    ): Box<T>? {
        connection.soTimeout = 15_000
        val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = runCatching { reader.readLine() }.getOrNull() ?: return null
        while (true) {
            val header = runCatching { reader.readLine() }.getOrNull() ?: break
            if (header.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val url = parts.getOrNull(1)?.let { "http://127.0.0.1$it".toHttpUrlOrNull() }
        if (parts.firstOrNull() != "GET" || url == null || url.encodedPath != path) {
            write(connection, "404 Not Found", "text/plain; charset=utf-8", "Not found", null)
            return null
        }
        val params = (0 until url.querySize).associate { i ->
            url.queryParameterName(i) to url.queryParameterValue(i).orEmpty()
        }
        val (value, reply) = respond(params)
        when (reply) {
            is CallbackReply.Redirect -> write(connection, "302 Found", null, "", reply.url)
            is CallbackReply.Page -> write(connection, "200 OK", "text/html; charset=utf-8", page(reply), null)
        }
        return Box(value)
    }

    private fun write(connection: Socket, status: String, type: String?, body: String, location: String?) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            type?.let { append("Content-Type: ").append(it).append("\r\n") }
            location?.let { append("Location: ").append(it).append("\r\n") }
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
        }
        runCatching {
            connection.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(bytes)
                flush()
            }
        }
    }

    private fun page(reply: CallbackReply.Page): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${esc(reply.title)}</title></head>" +
            "<body style=\"font-family:sans-serif;padding:32px;line-height:1.5\">" +
            "<h2>${esc(reply.title)}</h2><p>${esc(reply.message)}</p></body></html>"
    }

    override fun close() {
        runCatching { channel.close() }
    }

    companion object {
        /** Binds [preferredPort] on 127.0.0.1, or any free port when it is taken. */
        fun open(preferredPort: Int): LoopbackCallbackServer {
            val address = InetAddress.getByName("127.0.0.1")
            fun bindOn(port: Int): ServerSocketChannel = ServerSocketChannel.open().apply {
                try {
                    bind(InetSocketAddress(address, port), 8)
                } catch (error: IOException) {
                    close()
                    throw error
                }
            }
            val channel = runCatching { bindOn(preferredPort) }.getOrElse { bindOn(0) }
            return LoopbackCallbackServer(channel)
        }
    }
}

/** Opens [url] in the user's browser from any context. */
internal fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** Executes [request] on the shared client; returns status and body. Cancelling aborts the call. */
internal suspend fun executeForText(request: Request): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    val call = HttpClient.client.newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(OAuthException(e.message ?: "Network error"))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { it.code to it.body.string().take(1_000_000) } }
            if (!cont.isActive) return
            result.fold(
                onSuccess = { cont.resume(it) },
                onFailure = { cont.resumeWithException(OAuthException(it.message ?: "Read error")) },
            )
        }
    })
}
