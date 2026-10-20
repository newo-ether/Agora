package com.newoether.agora.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.widget.Toast
import com.newoether.agora.R
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** One advisory per generation, only at API dispatch or after a connection drops. */
internal class ApiConnectionNotice(
    private val context: Context,
    private val providerName: String,
    private val baseUrl: String?,
) {
    private var shown = false

    suspend fun showIfDisconnected() {
        if (shown || providerName == Constants.PROVIDER_LOCAL) return
        val host = runCatching { baseUrl?.let { URI(it).host } }.getOrNull()
        if (host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) return
        // An unavailable connectivity service is unknown, not proof of a lost connection.
        val disconnected = runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.let { it.activeNetwork == null } == true
        }.getOrDefault(false)
        if (!disconnected) return
        shown = true
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context.applicationContext, R.string.api_connection_required, Toast.LENGTH_LONG).show()
        }
    }
}
