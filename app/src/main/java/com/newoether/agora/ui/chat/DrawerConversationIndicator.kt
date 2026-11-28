package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.observeParentConversationId
import com.newoether.agora.ui.chat.interaction.InteractionKind
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal enum class DrawerConversationIndicator { NONE, GENERATING, UNREAD, QUESTION, APPROVAL }

internal fun resolveDrawerConversationIndicator(
    isGenerating: Boolean,
    isSelected: Boolean,
    hasUnreadGeneration: Boolean,
    interaction: InteractionKind? = null,
): DrawerConversationIndicator = when {
    interaction == InteractionKind.Approval -> DrawerConversationIndicator.APPROVAL
    interaction == InteractionKind.Question -> DrawerConversationIndicator.QUESTION
    isGenerating -> DrawerConversationIndicator.GENERATING
    hasUnreadGeneration && !isSelected -> DrawerConversationIndicator.UNREAD
    else -> DrawerConversationIndicator.NONE
}

/** Pending children surface on their visible parent without loading conversation payloads. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun ConversationRepository.observeDrawerInteractions(
    questions: Flow<List<AskUserController.Request>>,
    shell: Flow<ShellConfirmationController.PendingShellCommand?>,
): Flow<Map<String?, InteractionKind>> = combine(questions, shell) { requests, approval ->
    buildMap<String?, InteractionKind> {
        requests.forEach { put(it.conversationId, InteractionKind.Question) }
        approval?.let { put(it.conversationId, InteractionKind.Approval) }
    }
}.distinctUntilChanged().flatMapLatest { kinds ->
    val ids = kinds.keys.filterNotNull()
    if (ids.isEmpty()) flowOf(kinds)
    else combine(ids.map { id -> observeParentConversationId(id).map { id to it } }) { parents ->
        kinds.toMutableMap().apply {
            parents.forEach { (id, parent) ->
                if (parent != null && get(parent) != InteractionKind.Approval) put(parent, kinds.getValue(id))
            }
        }.toMap()
    }
}.distinctUntilChanged()

internal fun Map<String?, InteractionKind>.drawerInteraction(id: String): InteractionKind? =
    if (this[id] == InteractionKind.Approval || this[null] == InteractionKind.Approval) InteractionKind.Approval
    else this[id] ?: this[null]

@Composable
internal fun DrawerStatus(indicator: DrawerConversationIndicator, tint: Color) {
    AnimatedVisibility(indicator == DrawerConversationIndicator.GENERATING,
        enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        MotionAwareCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
    }
    AnimatedVisibility(indicator == DrawerConversationIndicator.QUESTION || indicator == DrawerConversationIndicator.APPROVAL,
        enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        val kind = if (indicator == DrawerConversationIndicator.APPROVAL) InteractionKind.Approval else InteractionKind.Question
        Icon(Icons.Outlined.QuestionMark, stringResource(kind.titleRes), Modifier.size(18.dp), tint = tint)
    }
    val unread = stringResource(R.string.conversation_unread_generation)
    AnimatedVisibility(indicator == DrawerConversationIndicator.UNREAD,
        enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = unread })
    }
}
