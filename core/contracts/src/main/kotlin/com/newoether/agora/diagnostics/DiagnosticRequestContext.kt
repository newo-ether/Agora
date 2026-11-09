package com.newoether.agora.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticRequestContext(
    val requestId: String? = null,
    val conversationIdHash: String? = null,
    val runId: String? = null,
    val pass: Int? = null,
    val provider: String? = null,
    val model: String? = null,
    val requestKind: String? = null,
)
