package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.message.ChatMarkdownCodeBlock
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController
import com.newoether.agora.ui.motion.AnimatedContent
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.animateFloatAsState
import com.newoether.agora.ui.components.Button
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

private val ContentMaxWidth = 840.dp
internal val ScrollableContentMaxHeight = 200.dp
private const val AppearDurationMs = 180
private const val PageDurationMs = 350

/** One page of the interaction card. [key] identifies it across list changes. */
private sealed interface DeckPage {
    val key: String

    data class Shell(val pending: ShellConfirmationController.PendingShellCommand) : DeckPage {
        override val key: String get() = "shell:${pending.id}"
    }

    data class Question(val request: AskUserController.Request) : DeckPage {
        override val key: String get() = "question:${request.id}"
    }
}

/** The bar grows out of the composer, so it scales from its bottom edge rather than its centre. */
private val BottomOrigin = TransformOrigin(0.5f, 1f)

/**
 * Bottom bar that answers the requests in [interactions] of [conversationId] without covering the
 * conversation.
 *
 * Everything waiting is one card with one page per request and a "current / total" count in the
 * header: the shell confirmation first, because a command is held until it is decided, then every
 * question in the order it was asked. Back and Next move between pages, and Send on the last page
 * answers every question at once. Nothing here can be dismissed by tapping elsewhere; both kinds
 * of request are answered only by their own buttons, which keeps the shell confirmation a real
 * security gate.
 *
 * The card can be folded into a capsule so the conversation behind it can be read; tapping the
 * capsule opens it again. Folding never answers anything. The host owns the folded set
 * [minimizedIn] per conversation, so switching away and back keeps it.
 *
 * The card appears and leaves by scaling from the composer. Switching to another conversation that
 * is also waiting lets the old card leave first and then brings the new one in, so it never looks
 * like paging inside one card. A leaving card keeps its own requests until it is gone.
 *
 * [onHeightChanged] reports, in pixels, how far the host should lift whatever sits above the
 * composer: the measured card height scaled by the card's appear/leave progress, so the lift grows
 * and shrinks with the card and is exactly zero once nothing is shown. It is the only writer.
 * [onCardGone] tells the host that the card of a conversation has finished leaving.
 */
@Composable
internal fun UserInteractionBar(
    conversationId: String,
    interactions: List<UserInteraction>,
    autoWrapCodeBlocks: Boolean,
    onSubmitQuestions: (List<Pair<Long, AskUserController.Answer>>) -> Unit,
    onSkipQuestion: (Long) -> Unit,
    onShellDecision: (Long, Boolean, Boolean) -> Unit,
    onHeightChanged: (Float) -> Unit,
    minimizedIn: Set<String>,
    onMinimizedChange: (String, Boolean) -> Unit,
    drafts: QuestionDrafts,
    pageIn: Map<String, String>,
    onPageChange: (String, String) -> Unit,
    onCardGone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val visible = interactions.isNotEmpty()
    // The exit animation still needs a card to draw, so the last non-empty list stays around.
    var shown by remember { mutableStateOf(conversationId to interactions) }
    if (visible) shown = conversationId to interactions
    val spec = if (motionPolicy.allowContinuousMotion) tween<Float>(AppearDurationMs) else snap()
    // The card appears and leaves by scaling, so its measured height never shrinks on its own. The
    // lift is that height times the same appear/leave progress, which makes it follow the card and
    // end at exactly zero. This is the one place the lift is reported from, so no late measurement
    // of a leaving card can leave the host lifted.
    val liftFraction by animateFloatAsState(if (visible) 1f else 0f, spec, label = "interaction-lift")
    var cardHeight by remember { mutableFloatStateOf(0f) }
    val latestOnHeightChanged by rememberUpdatedState(onHeightChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { cardHeight * liftFraction }.collect { latestOnHeightChanged(it) }
    }
    val latestOnCardGone by rememberUpdatedState(onCardGone)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spec) + scaleIn(spec, initialScale = 0.9f, transformOrigin = BottomOrigin),
        exit = fadeOut(spec) + scaleOut(spec, targetScale = 0.9f, transformOrigin = BottomOrigin),
        modifier = modifier.fillMaxWidth(),
    ) {
        // One card per conversation. Both sides are waiting here; an empty side is the
        // AnimatedVisibility above.
        AnimatedContent(
            targetState = shown,
            contentKey = { (owner, _) -> owner },
            transitionSpec = {
                if (!motionPolicy.allowContinuousMotion) {
                    EnterTransition.None togetherWith ExitTransition.None using null
                } else {
                    // The new card waits until the old one has gone.
                    val enterSpec = tween<Float>(AppearDurationMs, delayMillis = AppearDurationMs)
                    (
                        fadeIn(enterSpec) +
                            scaleIn(enterSpec, initialScale = 0.9f, transformOrigin = BottomOrigin)
                        ) togetherWith (
                        fadeOut(spec) +
                            scaleOut(spec, targetScale = 0.9f, transformOrigin = BottomOrigin)
                        ) using null
                }
            },
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxWidth(),
            label = "interaction-card",
        ) { (owner, waiting) ->
            // Leaves composition only once this card has finished leaving.
            DisposableEffect(owner) {
                onDispose { latestOnCardGone(owner) }
            }
            InteractionDeck(
                interactions = waiting,
                autoWrapCodeBlocks = autoWrapCodeBlocks,
                onSubmitQuestions = onSubmitQuestions,
                onSkipQuestion = onSkipQuestion,
                onShellDecision = onShellDecision,
                onCardMeasured = { height -> cardHeight = height },
                minimized = owner in minimizedIn,
                onMinimizedChange = { folded -> onMinimizedChange(owner, folded) },
                drafts = drafts,
                pageKey = pageIn[owner],
                onPageChange = { key -> onPageChange(owner, key) },
            )
        }
    }
}

@Composable
private fun InteractionDeck(
    interactions: List<UserInteraction>,
    autoWrapCodeBlocks: Boolean,
    onSubmitQuestions: (List<Pair<Long, AskUserController.Answer>>) -> Unit,
    onSkipQuestion: (Long) -> Unit,
    onShellDecision: (Long, Boolean, Boolean) -> Unit,
    onCardMeasured: (Float) -> Unit,
    minimized: Boolean,
    onMinimizedChange: (Boolean) -> Unit,
    drafts: QuestionDrafts,
    pageKey: String?,
    onPageChange: (String) -> Unit,
) {
    val shell = interactions.firstNotNullOfOrNull { (it as? UserInteraction.ShellCommand)?.pending }
    val questions = interactions.filterIsInstance<UserInteraction.Question>().flatMap { it.requests }
    val pages = listOfNotNull<DeckPage>(shell?.let(DeckPage::Shell)) +
        questions.map(DeckPage::Question)
    // The card holds on to every draft it has shown. The host forgets a draft as soon as its
    // request is answered, but a card on its way out must keep showing what was picked.
    val shownDrafts = remember { HashMap<Long, QuestionDraft>() }
    val draftOf: (AskUserController.Request) -> QuestionDraft = { request ->
        shownDrafts.getOrPut(request.id) { drafts.of(request) }
    }
    // The fixed button row decides the shell command, so the checkbox state lives out here.
    val alwaysAllow = remember { mutableStateMapOf<Long, Boolean>() }
    if (pages.isEmpty()) return
    val keys = pages.map { it.key }
    // The host remembers the page by key, so a request joining or leaving the card never moves the
    // user off the page they were on; a page that left falls back to the first one.
    val current = pages.firstOrNull { it.key == pageKey } ?: pages.first()
    val index = keys.indexOf(current.key)
    val position = if (pages.size > 1) "${index + 1} / ${pages.size}" else null
    val back: (() -> Unit)? = if (index > 0) ({ onPageChange(keys[index - 1]) }) else null
    val next: (() -> Unit)? = if (index < pages.lastIndex) ({ onPageChange(keys[index + 1]) }) else null
    val motion = LocalAgoraMotionPolicy.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onCardMeasured(it.height.toFloat()) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.widthIn(max = ContentMaxWidth).padding(horizontal = 12.dp)) {
            MorphingInteractionCard(
                kind = if (current is DeckPage.Shell) InteractionKind.Approval else InteractionKind.Question,
                minimized = minimized,
                onMinimizedChange = onMinimizedChange,
            ) {
                // The header and the buttons stay put and simply show the current page; only the
                // content between them slides a full width, while the card follows its height.
                when (current) {
                    is DeckPage.Shell -> CardHeader(
                        icon = { tint ->
                            Icon(InteractionKind.Approval.icon, null, modifier = Modifier.size(18.dp), tint = tint)
                        },
                        title = stringResource(InteractionKind.Approval.titleRes),
                        detail = current.pending.server,
                        position = position,
                    )
                    is DeckPage.Question -> CardHeader(
                        icon = { tint ->
                            Icon(InteractionKind.Question.icon, null, modifier = Modifier.size(18.dp), tint = tint)
                        },
                        title = stringResource(InteractionKind.Question.titleRes),
                        position = position,
                    )
                }
                Spacer(Modifier.height(10.dp))
                AnimatedContent(
                    targetState = current,
                    contentKey = { it.key },
                    transitionSpec = {
                        if (!motion.allowContinuousMotion) {
                            EnterTransition.None togetherWith ExitTransition.None using null
                        } else {
                            val from = keys.indexOf(initialState.key)
                            val to = keys.indexOf(targetState.key)
                            // A page that left the card (answered) counts as behind the new one.
                            val forward = from < 0 || to >= from
                            val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
                            slideInHorizontally(tween(PageDurationMs)) { sign * it } togetherWith
                                slideOutHorizontally(tween(PageDurationMs)) { -sign * it } using
                                SizeTransform(clip = true) { _, _ -> tween<IntSize>(PageDurationMs) }
                        }
                    },
                    label = "interaction page",
                ) { page ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        when (page) {
                            is DeckPage.Shell -> ShellBody(
                                pending = page.pending,
                                autoWrapCodeBlocks = autoWrapCodeBlocks,
                                alwaysAllow = alwaysAllow[page.pending.id] == true,
                                onAlwaysAllowChange = { alwaysAllow[page.pending.id] = it },
                            )
                            is DeckPage.Question -> QuestionBody(
                                request = page.request,
                                draft = draftOf(page.request),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                when (current) {
                    is DeckPage.Shell -> ShellActions(
                        onNext = next,
                        onDecision = { allow ->
                            onShellDecision(
                                current.pending.id,
                                allow,
                                allow && alwaysAllow[current.pending.id] == true,
                            )
                        },
                    )
                    is DeckPage.Question -> QuestionActions(
                        requests = questions,
                        draftOf = draftOf,
                        onBack = back,
                        onNext = next,
                        onSubmit = onSubmitQuestions,
                        onSkip = onSkipQuestion,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ShellBody(
    pending: ShellConfirmationController.PendingShellCommand,
    autoWrapCodeBlocks: Boolean,
    alwaysAllow: Boolean,
    onAlwaysAllowChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ScrollableContentMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        ChatMarkdownCodeBlock(code = pending.summary, autoWrap = autoWrapCodeBlocks)
    }
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onAlwaysAllowChange(!alwaysAllow) }
            // Inset so the rounded highlight never cuts into the checkbox.
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = alwaysAllow, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.shell_confirm_always),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ShellActions(
    onNext: (() -> Unit)?,
    onDecision: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onNext?.let { TextButton(onClick = it) { Text(stringResource(R.string.ask_user_next)) } }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { onDecision(false) },
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text(stringResource(R.string.shell_confirm_deny)) }
        Spacer(Modifier.width(4.dp))
        Button(onClick = { onDecision(true) }) {
            Text(stringResource(R.string.shell_confirm_allow))
        }
    }
}

@Composable
internal fun CardHeader(
    icon: @Composable (Color) -> Unit,
    title: String,
    detail: String? = null,
    position: String? = null,
) {
    // The end padding keeps the header clear of the card's minimize button.
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        detail?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        position?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
