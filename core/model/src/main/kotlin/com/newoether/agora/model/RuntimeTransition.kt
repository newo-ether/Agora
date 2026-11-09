package com.newoether.agora.model

enum class CommandRejection {
    ILLEGAL_STATE,
    STALE_IDENTITY,
    DUPLICATE_RESULT,
}
data class Transition(
    val newState: RunState,
    val effects: List<RunEffect> = emptyList(),
    val rejection: CommandRejection? = null,
) {
    val accepted: Boolean get() = rejection == null
}
