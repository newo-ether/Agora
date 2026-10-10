package com.newoether.agora.webui

import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of one WebUI login attempt. */
internal sealed interface WebUiLoginResult {
    data class Success(val sessionToken: String) : WebUiLoginResult
    /** [attemptsLeft] failures remain before the lockout starts. */
    data class WrongPassword(val attemptsLeft: Int) : WebUiLoginResult
    /** Every attempt is refused until [untilMillis], including a correct password. */
    data class LockedOut(val untilMillis: Long) : WebUiLoginResult
    /** No password is set, so the WebUI cannot be entered at all. */
    data object NotConfigured : WebUiLoginResult
}

/**
 * Password check, global lockout and device-local durable sessions for the WebUI.
 *
 * The lockout is global, not per address: the WebUI has one user, and a per-address counter
 * would let an attacker rotate addresses. After [maxFailures] consecutive failures every attempt
 * is refused for [lockoutMillis]; the counter then starts again. A success resets it.
 *
 * Session digests share the password's DataStore transaction. Service and process restarts
 * preserve sessions; logout and password replacement revoke them durably.
 */
internal class WebUiAuth(
    private val store: WebUiSettingsStore,
    private val hasher: WebUiPasswordHasher = WebUiPasswordHasher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val lockoutMillis: Long = DEFAULT_LOCKOUT_MILLIS,
) {
    private val lock = Mutex()
    private var failures = 0
    private var lockedUntil = 0L
    suspend fun login(password: String): WebUiLoginResult {
        val now = clock()
        lock.withLock {
            if (now < lockedUntil) return WebUiLoginResult.LockedOut(lockedUntil)
        }
        val stored = store.passwordHash.first() ?: return WebUiLoginResult.NotConfigured
        // Hash outside the lock: PBKDF2 is slow and must not block session checks.
        val matches = hasher.verify(password, stored)
        lock.withLock {
            // Another attempt may have started the lockout while this one was hashing.
            if (clock() < lockedUntil) return WebUiLoginResult.LockedOut(lockedUntil)
            if (store.passwordHash.first() != stored) return WebUiLoginResult.NotConfigured
            if (matches) {
                val token = newToken()
                if (!store.addSession(stored, digest(token))) return WebUiLoginResult.NotConfigured
                failures = 0
                return WebUiLoginResult.Success(token)
            }
            failures += 1
            if (failures >= maxFailures) {
                failures = 0
                lockedUntil = clock() + lockoutMillis
                return WebUiLoginResult.LockedOut(lockedUntil)
            }
            return WebUiLoginResult.WrongPassword(maxFailures - failures)
        }
    }

    suspend fun isValidSession(token: String?): Boolean = token != null && digest(token) in store.sessionDigests.first()

    suspend fun logout(token: String?) {
        if (token == null) return
        store.removeSession(digest(token))
    }

    suspend fun revokeAllSessions() = store.clearSessions()

    /** Returns once [token] is no longer a valid session (logout, revocation or never valid). */
    suspend fun awaitSessionEnd(token: String) {
        store.sessionDigests.first { digest(token) !in it }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun digest(token: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
    )

    companion object {
        const val DEFAULT_MAX_FAILURES = 10
        const val DEFAULT_LOCKOUT_MILLIS = 5 * 60 * 1000L
        private const val TOKEN_BYTES = 32
    }
}
