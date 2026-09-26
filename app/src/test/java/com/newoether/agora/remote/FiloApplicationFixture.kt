package com.newoether.agora.remote

import okhttp3.Call
import okhttp3.OkHttpClient

// These fixtures test the decoded application contract, using the same injectable
// transport boundary as native mocks. Public E2E has separate real Go interop
// tests; production FiloClient always installs its encrypted channel by default.
internal fun applicationFixtureClient(address: String, token: String,
    calls: Call.Factory = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build(),
    mutationTimeoutMillis: Long = 210_000,
) = FiloClient(address, token, calls, mutationTimeoutMillis)
