package com.newoether.agora.data.repository

import com.newoether.agora.data.CustomProviderConfig

internal fun resolveStableProviderReference(reference: String, providers: List<CustomProviderConfig>): String {
    val normalized = reference.trim()
    return providers.firstOrNull { provider ->
        provider.name == normalized || provider.ownsIdentity(normalized)
    }?.providerId ?: normalized
}
