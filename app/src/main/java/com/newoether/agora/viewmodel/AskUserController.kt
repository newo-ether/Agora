package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates `ask_user` requests between the tool loop, which asks, and the interaction bar,
 * which answers.
 *
 * Requests stack: parallel conversations and repeated tool calls can all be waiting, so the state
 * is an ordered list instead of a single slot and every answer carries its request id. A request
 * suspends the tool call that made it until the user answers or skips it. There is no timeout: a
 * question the user has not seen yet must not decide itself, and only cancelling the generation
 * ends the wait without an answer.
 */
class AskUserController {
    data class Request(
        val id: Long,
        val conversationId: String?,
        val question: String,
        val options: List<String>,
        val allowMultiple: Boolean,
    )

    /** [answered] is false when the user skipped the request or it was no longer waiting. */
    data class Answer(val choices: List<String>, val answered: Boolean) {
        companion object {
            val Unanswered = Answer(emptyList(), answered = false)
        }
    }

    private val _requests = MutableStateFlow<List<Request>>(emptyList())

    /** Every request still waiting for an answer, oldest first. */
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()

    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Answer>>()
    private val nextRequestId = AtomicLong(1)

    /**
     * Identifies this process's request numbering. Notification actions carry it so an action built
     * by an earlier process can never answer a request created after a restart.
     */
    val notificationSessionId: String = java.util.UUID.randomUUID().toString()

    /** Looks up a request that is still waiting, for callers that only kept its id. */
    fun requestById(id: Long): Request? = _requests.value.firstOrNull { it.id == id }

    fun open(
        conversationId: String?,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Request {
        val request = Request(
            id = nextRequestId.getAndIncrement(),
            conversationId = conversationId,
            question = question,
            options = options,
            allowMultiple = allowMultiple,
        )
        waiters[request.id] = CompletableDeferred()
        _requests.update { it + request }
        return request
    }

    /**
     * Suspends until [request] is answered or skipped. The wait is unbounded; cancelling the
     * generation cancels this coroutine, and [forget] then drops the request from the bar.
     */
    suspend fun awaitAnswer(request: Request): Answer {
        val waiter = waiters[request.id] ?: return Answer.Unanswered
        return try {
            waiter.await()
        } finally {
            forget(request.id)
        }
    }

    /**
     * Answers a request. An id that is no longer waiting is ignored, so one request cannot be
     * answered twice and a stale answer cannot decide a newer request.
     */
    fun submit(id: Long, choices: List<String>) = complete(id, Answer(choices, answered = true))

    /** The user declined to answer. The waiting caller resumes with [Answer.Unanswered]. */
    fun dismiss(id: Long) = complete(id, Answer.Unanswered)

    private fun complete(id: Long, answer: Answer) {
        _requests.value.firstOrNull { it.id == id } ?: return
        val waiter = waiters[id]
        forget(id)
        waiter?.complete(answer)
    }

    private fun forget(id: Long) {
        waiters.remove(id)
        _requests.update { requests -> requests.filterNot { it.id == id } }
    }
}
