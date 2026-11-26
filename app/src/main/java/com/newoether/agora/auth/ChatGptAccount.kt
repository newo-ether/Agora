package com.newoether.agora.auth

import android.content.Context
import com.newoether.agora.api.OAuthTokenSource
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.UUID

/**
 * The persisted SIWC record. [hostId] and [clientId] outlive sign-out: the host id must stay
 * stable for this install and the issued client id is reused for later sign-ins of the account.
 */
@Serializable
internal data class ChatGptCredential(
    val hostId: String = "urn:uuid:${UUID.randomUUID()}",
    val clientId: String? = null,
    val subject: String? = null,
    val email: String? = null,
    val idToken: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0,
    val savedAt: Long = 0,
    val scopes: List<String> = emptyList(),
) {
    val signedIn: Boolean get() = !refreshToken.isNullOrBlank() && !clientId.isNullOrBlank()

    fun signedOut(): ChatGptCredential =
        copy(idToken = null, accessToken = null, refreshToken = null, expiresAt = 0, scopes = emptyList())
}

/**
 * Sign in with ChatGPT for open-source agents (developers.openai.com/siwc/token-sharing-open-source).
 * Public PKCE client with dynamic registration, loopback redirect, ID-token validation, and
 * rotating refresh tokens. All token operations are serialized by one mutex, as the guide requires.
 */
class ChatGptAccount internal constructor(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : OAuthTokenSource, OAuthAccount {
    private val appContext = context.applicationContext
    private val file = OAuthCredentialFile(appContext, "chatgpt")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    @Volatile
    private var record: ChatGptCredential = load()

    private val _state = MutableStateFlow(stateOf(record))
    override val state: StateFlow<OAuthAccountState> = _state.asStateFlow()

    override val signedIn: Boolean get() = record.signedIn

    override suspend fun accessToken(): String? = mutex.withLock {
        val current = record
        if (!current.signedIn) return@withLock null
        val token = current.accessToken
        if (!token.isNullOrBlank() && clock() < current.expiresAt - REFRESH_MARGIN_MS) return@withLock token
        refreshLocked(current)
    }

    override suspend fun onUnauthorized(): Boolean = mutex.withLock {
        val current = record
        if (!current.signedIn) return@withLock false
        // A concurrent request already rotated the token after this one was sent.
        if (clock() - current.savedAt < RECENT_REFRESH_MS) return@withLock true
        refreshLocked(current) != null
    }

    /** Runs the whole browser flow; returns when the account is stored or the attempt failed. */
    override suspend fun signIn() {
        _state.update { it.copy(busy = true, error = null) }
        try {
            val result = authorize(record)
            mutex.withLock { save(result) }
            _state.value = stateOf(record)
        } catch (error: TimeoutCancellationException) {
            _state.value = stateOf(record).copy(error = "Sign-in timed out")
        } catch (error: CancellationException) {
            _state.value = stateOf(record)
            throw error
        } catch (error: Exception) {
            DebugLog.w(TAG, "sign-in failed: ${error.javaClass.simpleName}")
            _state.value = stateOf(record).copy(error = error.message ?: error.javaClass.simpleName)
        }
    }

    /** Revokes the refresh token when the server publishes an endpoint, then forgets the tokens. */
    override suspend fun signOut() {
        val current = mutex.withLock { record.also { save(it.signedOut()) } }
        _state.value = stateOf(record)
        val refresh = current.refreshToken ?: return
        val clientId = current.clientId ?: return
        runCatching {
            val endpoint = OpenAiOidc.revocationEndpoint() ?: return
            val body = FormBody.Builder()
                .add("token", refresh)
                .add("token_type_hint", "refresh_token")
                .add("client_id", clientId)
                .build()
            executeForText(Request.Builder().url(endpoint).post(body).build())
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun authorize(previous: ChatGptCredential): ChatGptCredential {
        val registered = previous.clientId?.takeIf(String::isNotBlank)
        val state = Pkce.randomToken()
        val nonce = Pkce.randomToken()
        val verifier = Pkce.randomToken(48)
        LoopbackCallbackServer.open(PREFERRED_PORT).use { server ->
            val redirectUri = "http://127.0.0.1:${server.port}$CALLBACK_PATH"
            val url = "$ISSUER/api/accounts/authorize".toHttpUrl().newBuilder().apply {
                addQueryParameter("client_id", registered ?: DYNAMIC_CLIENT)
                if (registered == null) addQueryParameter("agent_name_hint", AGENT_NAME)
                addQueryParameter("ext_agent_host_id", previous.hostId)
                if (registered != null) previous.email?.let { addQueryParameter("login_hint", it) }
                addQueryParameter("response_type", "code")
                addQueryParameter("redirect_uri", redirectUri)
                addQueryParameter("scope", SCOPE)
                addQueryParameter("resource", RESOURCE)
                addQueryParameter("state", state)
                addQueryParameter("nonce", nonce)
                addQueryParameter("code_challenge", Pkce.challenge(verifier))
                addQueryParameter("code_challenge_method", "S256")
            }.build().toString()
            openInBrowser(appContext, url)
            return withTimeout(SIGN_IN_TIMEOUT_MS) {
                server.await(CALLBACK_PATH) { params ->
                    val outcome = runCatching {
                        if (params["state"] != state) throw OAuthException("Sign-in state mismatch")
                        params["error"]?.let { error ->
                            throw OAuthException(
                                if (error == "access_denied") "ChatGPT sign-in was cancelled"
                                else params["error_description"] ?: error,
                            )
                        }
                        val code = params["code"]?.takeIf(String::isNotBlank)
                            ?: throw OAuthException("Callback has no authorization code")
                        val issued = params["client_id"]?.takeIf(String::isNotBlank)
                        val clientId = when {
                            registered == null -> issued?.takeIf { it != DYNAMIC_CLIENT }
                                ?: throw OAuthException("Registration did not return a client id")
                            issued != null && issued != registered ->
                                throw OAuthException("Callback returned a different client id")
                            else -> registered
                        }
                        exchange(previous, clientId, code, verifier, redirectUri, nonce, params["scope"])
                    }
                    val reply = outcome.fold(
                        onSuccess = { CallbackReply.Page("Signed in", "You can return to Agora now.") },
                        onFailure = { CallbackReply.Page("Sign-in failed", it.message ?: "Unknown error") },
                    )
                    outcome to reply
                }.getOrThrow()
            }
        }
    }

    private suspend fun exchange(
        previous: ChatGptCredential,
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
        nonce: String,
        callbackScope: String?,
    ): ChatGptCredential {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .add("resource", RESOURCE)
            .build()
        val tokens = tokenRequest(body)
        val idToken = tokens.string("id_token") ?: throw OAuthException("Token response has no ID token")
        val identity = OpenAiIdToken.verify(idToken, clientId, nonce, clock())
        if (previous.signedIn && previous.subject != null && previous.subject != identity.subject) {
            throw OAuthException("A different ChatGPT account signed in; sign out first")
        }
        val scopes = (tokens.string("scope") ?: callbackScope.orEmpty()).split(' ', '+').filter(String::isNotBlank)
        if (DIRECT_SCOPE !in scopes) {
            throw OAuthException("ChatGPT plan use was not granted. Enable it for this agent and sign in again.")
        }
        return previous.copy(
            clientId = clientId,
            subject = identity.subject,
            email = identity.email,
            idToken = idToken,
        ).withTokens(tokens, scopes)
    }

    /** Must hold [mutex]. Returns the new access token, or null after the session ended. */
    private suspend fun refreshLocked(current: ChatGptCredential): String? {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", current.clientId.orEmpty())
            .add("refresh_token", current.refreshToken.orEmpty())
            .add("resource", RESOURCE)
            .build()
        val tokens = try {
            tokenRequest(body)
        } catch (error: SessionEndedException) {
            DebugLog.w(TAG, "refresh rejected: ${error.message}")
            save(current.signedOut())
            _state.value = stateOf(record).copy(error = "ChatGPT session ended; sign in again")
            return null
        }
        val scopes = tokens.string("scope")?.split(' ')?.filter(String::isNotBlank) ?: current.scopes
        save(current.withTokens(tokens, scopes))
        return record.accessToken
    }

    private fun ChatGptCredential.withTokens(tokens: JsonObject, scopes: List<String>): ChatGptCredential {
        val now = clock()
        val expiresIn = (tokens["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L
        return copy(
            accessToken = tokens.string("access_token") ?: throw OAuthException("Token response has no access token"),
            // The refresh token rotates on every use; the old one is now invalid.
            refreshToken = tokens.string("refresh_token") ?: refreshToken
                ?: throw OAuthException("Token response has no refresh token"),
            idToken = tokens.string("id_token") ?: idToken,
            expiresAt = now + expiresIn * 1000,
            savedAt = now,
            scopes = scopes,
        )
    }

    private class SessionEndedException(message: String) : OAuthException(message)

    private suspend fun tokenRequest(body: FormBody): JsonObject {
        val (code, text) = executeForText(Request.Builder().url("$ISSUER/api/accounts/oauth/token").post(body).build())
        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code in 200..299) return parsed ?: throw OAuthException("Unreadable token response")
        val error = parsed?.string("error") ?: parsed?.get("error")?.let { (it as? JsonObject)?.string("code") }
        val description = parsed?.string("error_description") ?: error ?: "HTTP $code"
        if (error in SESSION_ENDED_ERRORS || code == 401) throw SessionEndedException(description)
        throw OAuthException(description)
    }

    private fun load(): ChatGptCredential =
        file.read()?.let { runCatching { json.decodeFromString(ChatGptCredential.serializer(), it) }.getOrNull() }
            ?: ChatGptCredential().also { runCatching { file.write(json.encodeToString(ChatGptCredential.serializer(), it)) } }

    private fun save(next: ChatGptCredential) {
        file.write(json.encodeToString(ChatGptCredential.serializer(), next))
        record = next
    }

    private fun stateOf(credential: ChatGptCredential) =
        OAuthAccountState(signedIn = credential.signedIn, account = credential.email.takeIf { credential.signedIn })

    private companion object {
        const val TAG = "ChatGptAccount"
        const val ISSUER = OpenAiOidc.ISSUER
        const val DYNAMIC_CLIENT = "dynamic_agent_client"
        const val AGENT_NAME = "Agora"
        const val PREFERRED_PORT = 1455
        const val CALLBACK_PATH = "/auth/callback"
        const val RESOURCE = "https://api.openai.com/v1"
        const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPE = "openid profile email offline_access resource.invoke $DIRECT_SCOPE"
        const val REFRESH_MARGIN_MS = 2 * 60 * 1000L
        const val RECENT_REFRESH_MS = 10_000L
        const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000L
        val SESSION_ENDED_ERRORS = setOf(
            "invalid_grant",
            "refresh_token_expired",
            "refresh_token_reused",
            "refresh_token_invalidated",
            "token_revoked",
        )
    }
}
