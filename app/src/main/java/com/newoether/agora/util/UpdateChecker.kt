package com.newoether.agora.util

data class UpdateInfo(
    val version: String,
    val url: String,
    val body: String
)

object UpdateChecker {
    /**
     * Update check prompt/hook disabled for XNet fork.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun check(currentVersion: String): UpdateInfo? = null
}
