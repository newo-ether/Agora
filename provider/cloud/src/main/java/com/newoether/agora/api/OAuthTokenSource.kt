package com.newoether.agora.api

/**
 * Account credentials owned by the app layer. A provider backed by one ignores
 * [ProviderConfig.apiKey] and asks here for the current access token on every request.
 */
interface OAuthTokenSource {
    /** True while a usable credential is stored; drives the "configured" state of the provider. */
    val signedIn: Boolean

    /** Current access token, refreshed first when it is about to expire. Null when signed out. */
    suspend fun accessToken(): String?

    /**
     * The server answered 401. Returns true when a fresh token is now available and the request
     * may be replayed once; false when the user has to sign in again.
     */
    suspend fun onUnauthorized(): Boolean
}

/** A provider whose credentials come from an account sign-in instead of an API key. */
interface OAuthBackedProvider {
    val tokenSource: OAuthTokenSource
}
