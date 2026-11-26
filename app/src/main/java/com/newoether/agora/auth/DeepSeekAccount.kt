package com.newoether.agora.auth

import android.content.Context
import android.os.Build
import com.newoether.agora.api.OAuthTokenSource
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Serializable
internal data class DeepSeekCredential(
    /** Stable per install, sent with every exchange as DSH does. */
    val deviceId: String = UUID.randomUUID().toString(),
    val token: String? = null,
    val account: String? = null,
)

/**
 * DeepSeek account sign-in using the platform protocol of DeepSeek Harness
 * (github.com/deepseek-ai/deepseek-harness, packages/account/deepseek-account). The token has no
 * expiry or refresh; the platform rejecting it (401 or code 40003) ends the session.
 */
class DeepSeekAccount internal constructor(
    context: Context,
    private val appVersion: String,
) : OAuthTokenSource, OAuthAccount {
    private val appContext = context.applicationContext
    private val file = OAuthCredentialFile(appContext, "deepseek")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var record: DeepSeekCredential = load()

    /** Last wallet read for the current token; memory only, re-read when the page opens. */
    @Volatile
    private var details: List<AccountDetail> = emptyList()

    private val _state = MutableStateFlow(stateOf(record))
    override val state: StateFlow<OAuthAccountState> = _state.asStateFlow()

    override val signedIn: Boolean get() = !record.token.isNullOrBlank()

    override suspend fun accessToken(): String? = record.token?.takeIf(String::isNotBlank)

    override suspend fun onUnauthorized(): Boolean {
        if (signedIn) {
            details = emptyList()
            save(record.copy(token = null, account = null))
            _state.value = stateOf(record).copy(error = "DeepSeek session ended; sign in again")
        }
        return false
    }

    override suspend fun signIn() {
        _state.update { it.copy(busy = true, error = null) }
        try {
            authorize()
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

    /** Ends the platform session, then forgets the token even when the server is unreachable. */
    override suspend fun signOut() {
        val token = record.token
        details = emptyList()
        save(record.copy(token = null, account = null))
        _state.value = stateOf(record)
        if (token.isNullOrBlank()) return
        runCatching {
            val request = Request.Builder().url("$ORIGIN/auth-api/v0/users/logout")
                .headers(clientHeaders())
                .header(TOKEN_HEADER, token)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            executeForText(request)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** Reads recharge and granted wallets the way DSH's account page does (get_user_summary). */
    override suspend fun refreshDetails() {
        val token = record.token?.takeIf(String::isNotBlank) ?: return
        val request = Request.Builder().url("$ORIGIN/api/v0/users/get_user_summary")
            .headers(clientHeaders())
            .header(TOKEN_HEADER, token)
            .get()
            .build()
        val summary = try {
            val (code, text) = executeForText(request)
            if (code == 401) {
                if (record.token == token) onUnauthorized()
                return
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val rootCode = (root?.get("code") as? JsonPrimitive)?.intOrNull
            if (rootCode == UNAUTHORIZED_CODE) {
                if (record.token == token) onUnauthorized()
                return
            }
            val data = root?.get("data") as? JsonObject
            if (code !in 200..299 || rootCode != 0 || (data?.get("biz_code") as? JsonPrimitive)?.intOrNull != 0) {
                DebugLog.w(TAG, "balance query failed: HTTP $code code=$rootCode")
                return
            }
            data?.get("biz_data") as? JsonObject ?: return
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.w(TAG, "balance query failed: ${error.javaClass.simpleName}")
            return
        }
        val next = listOfNotNull(
            walletText(summary["normal_wallets"])?.let { AccountDetail(AccountDetail.Kind.BALANCE, it) },
            walletText(summary["bonus_wallets"])?.let { AccountDetail(AccountDetail.Kind.BONUS_BALANCE, it) },
        )
        if (record.token != token) return
        details = next
        _state.update { it.copy(details = next) }
    }

    /** Sums wallets per currency, e.g. "¥12.30 · $1.00"; null when the field is malformed. */
    private fun walletText(element: JsonElement?): String? {
        val wallets = (element as? kotlinx.serialization.json.JsonArray) ?: return null
        val sums = linkedMapOf<String, java.math.BigDecimal>()
        for (wallet in wallets) {
            val obj = wallet as? JsonObject ?: return null
            val currency = obj.string("currency") ?: return null
            val amount = obj.string("balance")?.toBigDecimalOrNull() ?: return null
            sums[currency] = (sums[currency] ?: java.math.BigDecimal.ZERO) + amount
        }
        if (sums.isEmpty()) return "¥0.00"
        return sums.entries.joinToString(" · ") { (currency, amount) ->
            val symbol = when (currency) { "CNY" -> "¥"; "USD" -> "$"; else -> "$currency " }
            symbol + amount.setScale(2, java.math.RoundingMode.DOWN).toPlainString()
        }
    }

    private suspend fun authorize() {
        val verifier = Pkce.randomToken(48)
        val state = Pkce.randomToken()
        LoopbackCallbackServer.open(PREFERRED_PORT).use { server ->
            val redirectUri = "http://127.0.0.1:${server.port}$CALLBACK_PATH"
            val init = platform("auth_init", buildJsonObject {
                put("code_challenge", Pkce.challenge(verifier))
                put("code_challenge_method", "S256")
                put("state", state)
                put("redirect_uri", redirectUri)
                put("locale", wireLocale())
                put("login_source", "web")
            })
            val authorizeUrl = init.string("authorize_url")
                ?.takeIf { platformUrl(it, "/dsh/authorize") }
                ?: throw OAuthException("DeepSeek returned an unexpected sign-in page")
            val authorizeId = init.string("authorize_id")
            val expiresInMs = ((init["expires_in"] as? JsonPrimitive)?.intOrNull ?: 600) * 1000L
            openInBrowser(appContext, authorizeUrl)
            try {
                withTimeout(expiresInMs) {
                    server.await(CALLBACK_PATH) { params ->
                        val outcome = runCatching {
                            if (params["state"] != state) throw OAuthException("Sign-in state mismatch")
                            val code = params["code"]?.takeIf(String::isNotBlank)
                                ?: throw OAuthException("Callback has no authorization code")
                            exchange(code, verifier, redirectUri)
                        }
                        val reply = outcome.fold(
                            onSuccess = { CallbackReply.Redirect(it) },
                            onFailure = { CallbackReply.Page("Sign-in failed", it.message ?: "Unknown error") },
                        )
                        outcome to reply
                    }.getOrThrow()
                }
            } catch (error: Exception) {
                // Abandoned or failed attempts are withdrawn so the platform page stops waiting.
                if (authorizeId != null && !signedIn) {
                    withContext(NonCancellable) {
                        runCatching {
                            platform("auth_cancel", buildJsonObject {
                                put("authorize_id", authorizeId)
                                put("code_verifier", verifier)
                            })
                        }
                    }
                }
                throw error
            }
        }
    }

    /** Stores the token and returns the platform completion page for the browser. */
    private suspend fun exchange(code: String, verifier: String, redirectUri: String): String {
        val result = platform("auth_exchange", buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("redirect_uri", redirectUri)
            put("device_id", record.deviceId)
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("os_version", "Android ${Build.VERSION.RELEASE}")
        })
        val token = result.string("token")?.takeIf { it.matches(TOKEN_PATTERN) }
            ?: throw OAuthException("DeepSeek returned no usable token")
        val completion = result.string("authorized_url")?.takeIf { platformUrl(it, "/dsh/authorized") }
            ?: throw OAuthException("DeepSeek returned an unexpected completion page")
        save(record.copy(token = token, account = accountLabel(result["user"])))
        return completion.toHttpUrlOrNull()!!.newBuilder().setQueryParameter("login_source", "web").build().toString()
    }

    private suspend fun platform(method: String, body: JsonObject): JsonObject {
        val request = Request.Builder().url("$ORIGIN/auth-api/v0/dsh/$method")
            .headers(clientHeaders())
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val (code, text) = executeForText(request)
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code !in 200..299 || root == null) throw OAuthException("DeepSeek sign-in failed (HTTP $code)")
        val data = root["data"] as? JsonObject
        val bizCode = (data?.get("biz_code") as? JsonPrimitive)?.intOrNull
        if ((root["code"] as? JsonPrimitive)?.intOrNull != 0 || bizCode != 0) {
            val message = (data?.get("biz_msg") as? JsonPrimitive)?.content ?: root.string("msg")
            throw OAuthException(message?.takeIf(String::isNotBlank) ?: "DeepSeek sign-in was rejected")
        }
        return data?.get("biz_data") as? JsonObject ?: throw OAuthException("DeepSeek sign-in response is empty")
    }

    private fun clientHeaders(): okhttp3.Headers = okhttp3.Headers.Builder()
        .add("x-client-bundle-id", "")
        .add("x-client-platform", "web")
        .add("x-client-version", appVersion)
        .add("x-client-locale", wireLocale())
        .add("x-client-timezone-offset", (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toString())
        .add("User-Agent", "Agora/$appVersion")
        .build()

    private fun accountLabel(user: JsonElement?): String? {
        val obj = user as? JsonObject ?: return null
        val name = (obj["id_profile"] as? JsonObject)?.string("name")
        return sequenceOf(name, obj.string("mobile"), obj.string("mobile_number"), obj.string("email"))
            .firstOrNull { !it.isNullOrBlank() }
    }

    private fun platformUrl(value: String, path: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == HOST && url.encodedPath == path &&
            url.username.isEmpty() && url.fragment == null
    }

    private fun wireLocale(): String = if (Locale.getDefault().language == "zh") "zh_CN" else "en_US"

    private fun load(): DeepSeekCredential =
        file.read()?.let { runCatching { json.decodeFromString(DeepSeekCredential.serializer(), it) }.getOrNull() }
            ?: DeepSeekCredential().also { runCatching { file.write(json.encodeToString(DeepSeekCredential.serializer(), it)) } }

    @Synchronized
    private fun save(next: DeepSeekCredential) {
        file.write(json.encodeToString(DeepSeekCredential.serializer(), next))
        record = next
    }

    private fun stateOf(credential: DeepSeekCredential) = OAuthAccountState(
        signedIn = !credential.token.isNullOrBlank(),
        account = credential.account.takeIf { !credential.token.isNullOrBlank() },
        details = details.takeIf { !credential.token.isNullOrBlank() }.orEmpty(),
    )

    private companion object {
        const val TAG = "DeepSeekAccount"
        const val HOST = "platform.deepseek.com"
        const val ORIGIN = "https://$HOST"
        const val TOKEN_HEADER = "x-dsh-auth-token"
        const val UNAUTHORIZED_CODE = 40003
        const val PREFERRED_PORT = 1456
        const val CALLBACK_PATH = "/oauth/callback"
        val TOKEN_PATTERN = Regex("^[\\x21-\\x7e]+$")
        val JSON_TYPE = "application/json".toMediaType()
    }
}
