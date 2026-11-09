package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.Participant

/**
 * API-only text for automatic user input.
 *
 * The stored text stays clean and readable for the chat. The model instead receives an XML
 * envelope built from the structured [MessageSource], so it can tell a Task prompt, a Loop cycle
 * prompt and deferred ask_user answers apart from what the user typed, and sees skipped questions
 * explicitly. Content is XML-escaped so user text can never close or forge an element.
 */
internal fun projectAutomaticMessagesForApi(messages: List<ChatMessage>): List<ChatMessage> =
    messages.map { message ->
        val source = message.source?.takeIf { message.participant == Participant.USER }
            ?: return@map message
        message.copy(text = automaticMessageApiText(source, message.text))
    }

internal fun automaticMessageApiText(source: MessageSource, text: String): String = when (source.kind) {
    MessageSource.Kind.TASK -> element("task_prompt", text)
    MessageSource.Kind.LOOP -> element("loop_prompt", text)
    MessageSource.Kind.SUBAGENT -> element("subagent_result", text)
    MessageSource.Kind.ASK_USER -> buildString {
        append("<ask_user_answers>\n")
        source.askUser.forEach { item ->
            append("<item>\n")
            append("<question>").append(xmlEscape(item.question)).append("</question>\n")
            val answer = item.answer
            if (answer == null) {
                append("<unanswered/>\n")
            } else {
                append("<answer>").append(xmlEscape(answer)).append("</answer>\n")
            }
            append("</item>\n")
        }
        append("</ask_user_answers>")
    }
}

private fun element(name: String, text: String): String = "<$name>\n${xmlEscape(text)}\n</$name>"

private fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(char)
        }
    }
}
