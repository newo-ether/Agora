package com.newoether.agora.util

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlin.random.Random

/** Shared budget and classification; request owners decide whether replay is safe. */
object NetworkRetryPolicy {
    const val MAX_RETRIES = 5
    const val MAX_ATTEMPTS = MAX_RETRIES + 1
    val HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504, 529)
    private val messages = listOf(
        "unexpected end of stream", "unexpected end of file", "eofexception",
        "software caused connection abort", "socket is closed", "socket closed",
        "connection reset", "connection aborted", "broken pipe", "connection shutdown",
        "ssl peer shut down incorrectly", "connection closed by peer", "unexpected eof",
        "stream was reset", "protocol_error", "refused_stream", "timed out", "timeout",
        "the server response could not be read", "incomplete encrypted event line",
    )

    fun isTransientMessage(message: String?): Boolean =
        message != null && messages.any { message.contains(it, ignoreCase = true) }

    fun causes(failure: Throwable): List<Throwable> {
        val seen = mutableSetOf<Throwable>()
        return generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.toList()
    }

    fun throwIfCancelled(failure: Throwable) {
        causes(failure).filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    }

    fun isTransient(failure: Throwable): Boolean {
        val chain = causes(failure)
        if (chain.any { it is CancellationException || it is CertificateException ||
                it is SSLPeerUnverifiedException || it is ConchAuthenticationException
            }) return false
        if (chain.any { it is NetworkHttpException && it.statusCode !in HTTP_CODES }) return false
        return chain.any {
            it is EOFException || it is SocketTimeoutException || it is ConnectException ||
                it is UnknownHostException || it is SocketException ||
                it is InterruptedIOException ||
                (it is SSLException && isTransientMessage(it.message)) ||
                it.javaClass.name == "okhttp3.internal.http2.StreamResetException" ||
                it.javaClass.name == "okhttp3.internal.http2.ConnectionShutdownException" ||
                isTransientMessage(it.message)
        }
    }

    fun isBeforeSendFailure(failure: Throwable): Boolean =
        isTransient(failure) && causes(failure).any { it is ConnectException || it is UnknownHostException }

    /** Equal jitter: 0.5–1, 1–2, 2–4, 4–8, 8–16 seconds. */
    fun delayMillis(
        retryNumber: Int,
        retryAfter: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
    ): Long {
        require(retryNumber in 1..MAX_RETRIES)
        val ceiling = 1_000L shl (retryNumber - 1)
        val backoff = random.nextLong(ceiling / 2, ceiling + 1)
        return maxOf(backoff, retryAfterMillis(retryAfter, nowMillis) ?: 0L)
    }

    fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1_000) * 1_000 }
        return runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.isLenient = false
            ((format.parse(raw) ?: Date(nowMillis)).time - nowMillis).coerceAtLeast(0)
        }.getOrNull()
    }

    suspend fun <T> retry(
        canRetry: (Throwable) -> Boolean = {
            isTransient(it) || (it is NetworkHttpException && it.statusCode in HTTP_CODES)
        },
        retryAfter: () -> String? = { null },
        onRetry: suspend (Int) -> Unit = {},
        block: suspend () -> T,
    ): T {
        var retries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (failure: Exception) {
                throwIfCancelled(failure)
                currentCoroutineContext().ensureActive()
                if (retries == MAX_RETRIES || !canRetry(failure)) throw failure
                retries++
                onRetry(retries)
                delay(delayMillis(retries, retryAfter()))
            }
        }
    }
}
