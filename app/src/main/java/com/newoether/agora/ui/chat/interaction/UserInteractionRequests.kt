package com.newoether.agora.ui.chat.interaction

import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController

/**
 * One pending request the user has to answer before the tool loop that asked can continue.
 *
 * Questions and shell confirmations come from different controllers but occupy the same screen
 * space, so the bar treats them as one ordered list. [key] is stable per request and prefixed per
 * kind, because the two controllers number their requests independently.
 */
internal sealed interface UserInteraction {
    val key: String

    data class Question(val request: AskUserController.Request) : UserInteraction {
        override val key: String get() = "question:${request.id}"
    }

    data class ShellCommand(
        val pending: ShellConfirmationController.PendingShellCommand,
    ) : UserInteraction {
        override val key: String get() = "shell:${pending.id}"
    }
}

/**
 * Collects the requests the conversation [conversationId] must show, oldest first.
 *
 * A question without a conversation id came from a surface that has no chat of its own (a task
 * run, for example), so every conversation may answer it. Shell confirmations are process-wide by
 * design: only one is pending at a time and any visible chat may answer it.
 */
internal fun userInteractions(
    conversationId: String?,
    questions: List<AskUserController.Request>,
    shellCommand: ShellConfirmationController.PendingShellCommand?,
): List<UserInteraction> {
    val visibleQuestions = questions
        .filter { it.conversationId == null || it.conversationId == conversationId }
        .map { UserInteraction.Question(it) }
    val shell = shellCommand?.let { UserInteraction.ShellCommand(it) }
    return if (shell == null) visibleQuestions else visibleQuestions + shell
}
