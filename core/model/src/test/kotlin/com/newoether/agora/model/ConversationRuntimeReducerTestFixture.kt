package com.newoether.agora.model

internal object ConversationRuntimeReducerTestFixture {
    fun active(
        ownerToken: Long,
        runId: String,
        pass: Int = 0,
    ): RunState.Active = RunState.Active(identity(ownerToken, runId, pass))

    fun stopCommand(
        active: RunState.Active,
        effectId: String,
        coroutineSettled: Boolean = false,
    ) = ConversationCommand.StopRequested(
        identity = active.identity,
        coroutineAlreadySettled = coroutineSettled,
        requiresPersistence = true,
        effectId = effectId,
    )

    fun sendCommand(
        ownerToken: Long,
        runId: String,
        effectId: String,
        directOnly: Boolean = false,
        hasPendingGuidance: Boolean = false,
    ) = ConversationCommand.SendRequested(
        identity = RunEffectIdentity(
            conversationId = CONVERSATION_ID,
            ownerToken = ownerToken,
            runId = runId,
            pass = 0,
            effectId = effectId,
        ),
        directOnly = directOnly,
        hasPendingGuidance = hasPendingGuidance,
    )

    fun identity(
        ownerToken: Long,
        runId: String? = null,
        pass: Int = 0,
    ) = RuntimeRunIdentity(CONVERSATION_ID, ownerToken, runId, pass)

    fun effectIdentity(identity: RuntimeRunIdentity, effectId: String) =
        RunEffectIdentity(
            conversationId = identity.conversationId,
            ownerToken = identity.ownerToken,
            runId = requireNotNull(identity.runId),
            pass = identity.pass,
            effectId = effectId,
        )

    fun releaseEffect(transition: Transition): RunEffect.ReleaseSlot =
        transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().single()

    const val CONVERSATION_ID = "conversation"
}
