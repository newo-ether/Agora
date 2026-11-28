package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates `ask_user` requests between the tool loop, which asks, and the interaction bar,
 * which answers.
 *
 * Requests stack: parallel conversations and repeated tool calls can all be waiting, so the state
 * is an ordered list instead of a single slot and every answer carries its request id.
 *
 * A request is either blocking or not, and that choice belongs to the caller:
 *
 * - blocking: the tool call suspends until the answers arrive or its configured group budget
 *   expires. Expiry returns unanswered items without inventing a choice; stopping generation
 *   cancels the wait and removes its requests.
 * - non-blocking: the tool call returns at once and the model keeps working. The answer is
 *   published through [deferredAnswers] and reaches the model as a queued user message, the same
 *   path a message typed during a generation takes.
 */
class AskUserController {
    data class Request(
        val id: Long,
        val conversationId: String?,
        val question: String,
        val options: List<String>,
        val allowMultiple: Boolean,
        /** False means the asking tool call already returned and the answer travels as a message. */
        val blocking: Boolean,
        /**
         * Shared by the questions one call asked together, so they are shown as one card; null for
         * a question asked on its own. Each question is still answered by its own id.
         */
        val setId: Long? = null,
        val runId: String? = null,
    )

    /**
     * [choices] are the options the user picked, [text] is what they typed instead of, or next to,
     * an option. [answered] is false when the user skipped the request or it was no longer waiting.
     */
    data class Answer(
        val choices: List<String>,
        val text: String? = null,
        val answered: Boolean,
    ) {
        companion object {
            val Unanswered = Answer(emptyList(), null, answered = false)
        }
    }

    /** Answers to non-blocking requests, on their way to [conversationId] as one user message. */
    data class DeferredAnswer(val conversationId: String, val source: MessageSource) {
        init {
            require(source.kind == MessageSource.Kind.ASK_USER)
        }

        /** The clean readable message text; the model sees the structured [source]. */
        val text: String get() = source.askUserReadableText()
    }

    private val _requests = MutableStateFlow<List<Request>>(emptyList())

    /** Every request still waiting for an answer, oldest first. */
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()

    private val _deferredAnswers = MutableSharedFlow<DeferredAnswer>(extraBufferCapacity = 32)

    /**
     * Answers to non-blocking requests. The process container's one delivery collects this and sends each one through
     * the queue, so an answer given while the model is still working lands in the next turn.
     */
    val deferredAnswers: SharedFlow<DeferredAnswer> = _deferredAnswers.asSharedFlow()

    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Answer>>()
    private val nextRequestId = AtomicLong(1)

    /**
     * Identifies this process's request numbering. Notification actions carry it so an action built
     * by an earlier process can never answer a request created after a restart.
     */
    val notificationSessionId: String = java.util.UUID.randomUUID().toString()

    /** A fresh id for a set of questions opened together; it never equals a request id. */
    fun newSetId(): Long = nextRequestId.getAndIncrement()

    /** Looks up a request that is still waiting, for callers that only kept its id. */
    fun requestById(id: Long): Request? = _requests.value.firstOrNull { it.id == id }

    fun open(
        conversationId: String?,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
        blocking: Boolean,
        setId: Long? = null,
        runId: String? = null,
    ): Request = synchronized(waiters) {
        val request = Request(
            id = nextRequestId.getAndIncrement(),
            conversationId = conversationId,
            question = question,
            options = options,
            allowMultiple = allowMultiple,
            blocking = blocking,
            setId = setId,
            runId = runId,
        )
        // Only a blocking request has a caller to resume.
        if (blocking) waiters[request.id] = CompletableDeferred()
        _requests.update { it + request }
        request
    }

    /**
     * Suspends until [request] is answered or skipped. The wait is unbounded; cancelling the
     * generation cancels this coroutine, and the request then leaves the bar.
     */
    suspend fun awaitAnswer(request: Request): Answer {
        // The waiter outlives the request on purpose: one tool call can be waiting on several
        // questions, and an answer given before its turn to be collected must not be lost.
        val waiter = waiters[request.id] ?: return Answer.Unanswered
        return try {
            waiter.await()
        } finally {
            forget(request.id)
        }
    }

    /**
     * Waits once for the whole call. Keep waiter references until expiry so an answer submitted out
     * of order survives even when an earlier question remains unanswered. Cancellation propagates.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitAnswers(requests: List<Request>, timeoutMs: Long): List<Answer> {
        val pending = synchronized(waiters) { requests.map { waiters[it.id] } }
        return try {
            withTimeoutOrNull(timeoutMs) {
                pending.map { it?.await() ?: Answer.Unanswered }
            } ?: synchronized(waiters) {
                // Snapshot and close admission together with submitAll's claim/completion.
                pending.map { waiter ->
                    if (waiter?.isCompleted == true) waiter.getCompleted() else Answer.Unanswered
                }.also { requests.forEach { forget(it.id) } }
            }
        } finally {
            synchronized(waiters) { requests.forEach { forget(it.id) } }
        }
    }

    /**
     * Gives up on [id]: its card leaves the bar and its answer, if any, is dropped. Callers use this
     * when their wait ends for their own reasons, such as the generation being stopped, so nothing
     * is left on screen that no longer has anyone to return to. It is idempotent.
     */
    fun abandon(id: Long) = synchronized(waiters) {
        forget(id)
    }

    /**
     * Answers a request with the options [choices] and the typed [text], either of which may be
     * empty. An id that is no longer waiting is ignored, so one request cannot be answered twice and
     * a stale answer cannot decide a newer request.
     */
    fun submit(id: Long, choices: List<String>, text: String? = null) {
        submitAll(listOf(id to Answer(choices, text, answered = true)))
    }

    /**
     * Answers several requests with one Send, in the order the user saw them. An [Answer] with
     * `answered = false` marks a question left blank. Ids no longer waiting are ignored.
     *
     * Blocking requests resume their own callers. The non-blocking ones of one conversation become
     * a single user message, so one Send is one turn for the model rather than a queue of them;
     * blank questions are named in it so the model knows they went unanswered.
     */
    fun submitAll(answers: List<Pair<Long, Answer>>) = synchronized(waiters) {
        // The whole set leaves the card in one atomic update, so the card never shows a half-sent
        // state and a request can be claimed by only one Send. A blocking answer stays until its
        // caller collects it.
        var answered = emptyList<Pair<Request, Answer>>()
        _requests.update { requests ->
            val waiting = requests.associateBy { it.id }
            answered = answers.mapNotNull { (id, given) -> waiting[id]?.let { it to given } }
            val answeredIds = answered.mapTo(HashSet()) { it.first.id }
            requests.filterNot { it.id in answeredIds }
        }
        if (answered.isEmpty()) return@synchronized
        val deferred = LinkedHashMap<String, MutableList<Pair<Request, Answer>>>()
        for ((request, given) in answered) {
            val id = request.id
            val answer = given.copy(text = given.text?.takeIf { it.isNotBlank() })
            if (request.blocking) {
                waiters[id]?.complete(if (answer.answered) answer else Answer.Unanswered)
                continue
            }
            // A non-blocking request without a conversation has nowhere to deliver the answer; the
            // tool refuses that combination, so this is only a guard.
            val conversationId = request.conversationId ?: continue
            deferred.getOrPut(conversationId) { mutableListOf() } += request to answer
        }
        deferred.forEach { (conversationId, items) ->
            // Nothing answered means nothing said, so a fully blank set sends no message.
            if (items.none { it.second.answered }) return@forEach
            val source = MessageSource.askUser(
                items.map { (request, answer) -> deferredAnswerItem(request, answer) },
            )
            _deferredAnswers.tryEmit(DeferredAnswer(conversationId, source))
        }
    }

    /** The user declined to answer. A blocking caller resumes with [Answer.Unanswered]. */
    fun dismiss(id: Long) = synchronized(waiters) {
        val request = _requests.value.firstOrNull { it.id == id } ?: return@synchronized
        dropRequest(id)
        if (request.blocking) waiters[id]?.complete(Answer.Unanswered)
    }

    private fun forget(id: Long) = synchronized(waiters) {
        waiters.remove(id)
        dropRequest(id)
    }

    private fun dropRequest(id: Long) {
        _requests.update { requests -> requests.filterNot { it.id == id } }
    }

    companion object {
        /**
         * One question/answer pair of a non-blocking answer message. It keeps the question because
         * the message arrives one or more turns after the model asked, where the question is no
         * longer the last thing said. A question the user left blank has no answer.
         */
        fun deferredAnswerItem(request: Request, answer: Answer): MessageSource.AskUserItem =
            MessageSource.AskUserItem(
                question = request.question,
                answer = if (answer.answered) answerBody(answer) else null,
            )

        /** The user's answer on its own: picked options first, then whatever they typed. */
        fun answerBody(answer: Answer): String = listOfNotNull(
            answer.choices.takeIf { it.isNotEmpty() }?.joinToString(", "),
            answer.text?.takeIf { it.isNotBlank() },
        ).joinToString("\n").ifBlank { "" }
    }
}
