package com.newoether.agora.data.local

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.templates.interactiveTemplate
import kotlinx.serialization.json.Json

/** Only a validated, completed local template may bypass ACTIVE generation admission. */
internal fun validateLocalOrActiveRunAdmission(run: RunEntity, messages: List<MessageEntity>) {
    if (run.status == RunStatus.ACTIVE) {
        require(run.activeSlot == 1)
        return
    }
    require(run.status == RunStatus.COMPLETED && run.endReason == RunEndReason.LOCAL_TEMPLATE_OPENED)
    val message = messages.single()
    require(message.text.isEmpty() && message.images.isEmpty() && message.participant == Participant.MODEL)
    val segment = Json.decodeFromString<List<MessageSegment>>(checkNotNull(message.toolCallJson)).single()
    require(segment.interactiveTemplate() != null && segment.type == "interactive_template")
}
