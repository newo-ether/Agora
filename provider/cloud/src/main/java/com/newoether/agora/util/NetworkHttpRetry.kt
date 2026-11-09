package com.newoether.agora.util

import com.newoether.agora.api.HttpClient
import com.newoether.agora.viewmodel.GenerationCancelHandle
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Attached only to Agora-owned calls. Header admission is a conservative replay fence. */
class NetworkRequestProgress {
    @Volatile var started = false
}

class NetworkHttpException(
    val statusCode: Int,
    val retryAfter: String?,
    message: String,
) : IOException(message)

/** Buffered reads retry as a whole, so a failed body is never returned as a partial success. */
fun <T> OkHttpClient.executeWithNetworkRetry(
    request: Request,
    replaySafe: Boolean = true,
    callTimeoutMillis: Long? = null,
    maxRetries: Int = NetworkRetryPolicy.MAX_RETRIES,
    cancelled: () -> Boolean = HttpClient::isRequestCancelled,
    wait: (Int, String?, () -> Boolean) -> Unit = ::waitForNetworkRetry,
    read: (Response) -> T,
): T {
    val retryClient = newBuilder().retryOnConnectionFailure(false).apply {
        if (!replaySafe) { followRedirects(false); followSslRedirects(false) }
    }.build()
    var retries = 0
    val stopped = AtomicBoolean(false)
    val active = AtomicReference<Call?>(null)
    val scope = HttpClient.boundStreamScope()
    val handle = GenerationCancelHandle { stopped.set(true); active.get()?.cancel() }
    val isCancelled = { stopped.get() || cancelled() }
    scope?.register(handle)
    try {
        while (true) {
            if (isCancelled()) throw CancellationException("Network request cancelled")
            val progress = NetworkRequestProgress()
            val call = retryClient.newCall(request.newBuilder().tag(NetworkRequestProgress::class.java, progress).build())
            active.set(call)
            if (isCancelled()) call.cancel()
            callTimeoutMillis?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
            var retryAfter: String? = null
            var status: Int? = null
            try {
                call.execute().use { response ->
                    status = response.code
                    retryAfter = response.header("Retry-After")
                    if (!replaySafe || response.code !in NetworkRetryPolicy.HTTP_CODES ||
                        retries == maxRetries
                    ) return read(response)
                }
            } catch (failure: Exception) {
                NetworkRetryPolicy.throwIfCancelled(failure)
                if (isCancelled()) throw CancellationException("Network request cancelled", failure)
                if ((!replaySafe && (progress.started || !NetworkRetryPolicy.isBeforeSendFailure(failure))) ||
                    (status != null && status !in 200..299 && status !in NetworkRetryPolicy.HTTP_CODES) ||
                    retries == maxRetries || !NetworkRetryPolicy.isTransient(failure)
                ) throw failure
            } finally {
                active.compareAndSet(call, null)
            }
            retries++
            wait(retries, retryAfter, isCancelled)
        }
    } finally {
        scope?.unregister(handle)
    }
}

/** Synchronous callers already run on IO; wait in short slices so Stop can abort the backoff. */
internal fun waitForNetworkRetry(
    retryNumber: Int,
    retryAfter: String?,
    cancelled: () -> Boolean = HttpClient::isRequestCancelled,
) {
    var remainingMillis = NetworkRetryPolicy.delayMillis(retryNumber, retryAfter)
    while (remainingMillis > 0) {
        if (cancelled()) throw CancellationException("Network request cancelled")
        val slice = minOf(remainingMillis, 100L)
        try {
            Thread.sleep(slice)
            remainingMillis -= slice
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Network request interrupted", interrupted)
        }
    }
}
