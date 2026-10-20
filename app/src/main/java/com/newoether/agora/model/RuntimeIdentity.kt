package com.newoether.agora.model

/**
 * Durable lifecycle of one user-visible agentic execution.
 *
 * Provider calls are identified substates inside ACTIVE; they are not separate durable Runs.
 */
enum class RunStatus {
    ACTIVE,
    STOPPING,
    COMPLETED,
    STOPPED,
    FAILED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == STOPPED || this == FAILED
}
enum class RunEndReason {
    MODEL_COMPLETED,
    LOCAL_TEMPLATE_OPENED,
    CONTINUATION_PENDING,
    USER_STOPPED,
    PROCESS_RECOVERED,
    PROVIDER_ERROR,
}

/**
 * Identity of the in-process owner for one conversation Run.
 *
 * [ownerToken] is the legacy slot token retained during the strangler migration. [runId] is null
 * only between the synchronous slot claim and the Room transaction that creates/binds the Run.
 */
data class RuntimeRunIdentity(
    val conversationId: String,
    val ownerToken: Long,
    val runId: String? = null,
    val pass: Int = 0,
) {
    init {
        require(conversationId.isNotBlank())
        require(ownerToken > 0L)
        require(runId == null || runId.isNotBlank())
        require(pass >= 0)
    }
}

/**
 * Full Run-target identity echoed by every asynchronous effect result migrated here.
 * [RunEffect.PersistAcceptedInput] carries the proposed Run id before Room binds it; every later
 * effect carries an already-durable Run id.
 */
data class RunEffectIdentity(
    val conversationId: String,
    val ownerToken: Long,
    val runId: String,
    val pass: Int,
    val effectId: String,
) {
    init {
        require(conversationId.isNotBlank())
        require(ownerToken > 0L)
        require(runId.isNotBlank())
        require(pass >= 0)
        require(effectId.isNotBlank())
    }

    fun runIdentity(): RuntimeRunIdentity = RuntimeRunIdentity(
        conversationId = conversationId,
        ownerToken = ownerToken,
        runId = runId,
        pass = pass,
    )
}

/** Durable process-start snapshot for one live Run; no coroutine is ever reconstructed from it. */
data class RunRecoverySnapshot(
    val conversationId: String,
    val runId: String,
    val pass: Int,
    val status: RunStatus,
) {
    init {
        require(conversationId.isNotBlank())
        require(runId.isNotBlank())
        require(pass >= 0)
        require(status == RunStatus.ACTIVE || status == RunStatus.STOPPING)
    }
}
