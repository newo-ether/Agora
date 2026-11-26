package com.newoether.agora.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** OpenAI's OIDC discovery data, fetched once per process. */
internal object OpenAiOidc {
    const val ISSUER = "https://auth.openai.com"
    private const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var discovery: JsonObject? = null
    @Volatile private var jwks: JsonArray? = null

    suspend fun discovery(): JsonObject = discovery ?: getJson(DISCOVERY).also { discovery = it }

    suspend fun revocationEndpoint(): String? = discovery().string("revocation_endpoint")

    suspend fun keys(refresh: Boolean): JsonArray {
        if (!refresh) jwks?.let { return it }
        val uri = discovery().string("jwks_uri") ?: throw OAuthException("OpenAI discovery has no jwks_uri")
        val keys = getJson(uri)["keys"] as? JsonArray ?: throw OAuthException("OpenAI JWKS has no keys")
        jwks = keys
        return keys
    }

    private suspend fun getJson(url: String): JsonObject {
        val (code, body) = executeForText(Request.Builder().url(url).get().build())
        if (code !in 200..299) throw OAuthException("HTTP $code from $url")
        return runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw OAuthException("Unreadable response from $url") }
    }
}

/** Verified identity from a SIWC ID token. */
internal data class OpenAiIdentity(val subject: String, val email: String?, val plan: String? = null)

/**
 * Checks an ID token the way the SIWC guide requires: RS256 signature against OpenAI's JWKS, then
 * issuer, audience (the issued client id), expiry and the nonce of this attempt.
 */
internal object OpenAiIdToken {
    private val json = Json { ignoreUnknownKeys = true }
    private val decoder = Base64.getUrlDecoder()

    suspend fun verify(idToken: String, clientId: String, nonce: String, nowMillis: Long): OpenAiIdentity {
        val parts = idToken.split('.')
        if (parts.size != 3) throw OAuthException("Malformed ID token")
        val header = decodePart(parts[0])
        val payload = decodePart(parts[1])
        if (header.string("alg") != "RS256") throw OAuthException("Unexpected ID token algorithm")
        val kid = header.string("kid")
        val signed = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        val signature = runCatching { decoder.decode(parts[2]) }.getOrElse { throw OAuthException("Malformed ID token") }
        val valid = verifyWith(OpenAiOidc.keys(refresh = false), kid, signed, signature) ||
            verifyWith(OpenAiOidc.keys(refresh = true), kid, signed, signature)
        if (!valid) throw OAuthException("ID token signature is invalid")

        if (payload.string("iss") != OpenAiOidc.ISSUER) throw OAuthException("ID token issuer mismatch")
        val audience = when (val aud = payload["aud"]) {
            is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        if (clientId !in audience) throw OAuthException("ID token audience mismatch")
        val exp = (payload["exp"] as? JsonPrimitive)?.longOrNull ?: throw OAuthException("ID token has no expiry")
        if (exp * 1000 + CLOCK_SKEW_MS < nowMillis) throw OAuthException("ID token has expired")
        if (payload.string("nonce") != nonce) throw OAuthException("ID token nonce mismatch")
        val subject = payload.string("sub")?.takeIf(String::isNotBlank)
            ?: throw OAuthException("ID token has no subject")
        val plan = (payload[AUTH_CLAIM] as? JsonObject)?.string("chatgpt_plan_type")
        return OpenAiIdentity(subject, payload.string("email"), plan)
    }

    private fun verifyWith(keys: JsonArray, kid: String?, signed: ByteArray, signature: ByteArray): Boolean =
        keys.mapNotNull { it as? JsonObject }
            .filter { it.string("kty") == "RSA" && (kid == null || it.string("kid") == kid) }
            .any { key ->
                runCatching {
                    val n = BigInteger(1, decoder.decode(checkNotNull(key.string("n"))))
                    val e = BigInteger(1, decoder.decode(checkNotNull(key.string("e"))))
                    val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
                    Signature.getInstance("SHA256withRSA").run {
                        initVerify(publicKey)
                        update(signed)
                        verify(signature)
                    }
                }.getOrDefault(false)
            }

    private fun decodePart(part: String): JsonObject = runCatching {
        json.parseToJsonElement(String(decoder.decode(part), Charsets.UTF_8)).jsonObject
    }.getOrElse { throw OAuthException("Malformed ID token") }

    private const val CLOCK_SKEW_MS = 5 * 60 * 1000L
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
