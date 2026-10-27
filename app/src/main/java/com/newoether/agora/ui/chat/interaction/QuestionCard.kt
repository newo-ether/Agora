package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.newoether.agora.viewmodel.AskUserController

private const val FieldDurationMs = 180

/** What the user has picked and typed for one question, kept while they move between pages. */
@Stable
internal class QuestionDraft(hasOptions: Boolean) {
    var selected by mutableStateOf(emptySet<String>())
    var typed by mutableStateOf("")

    // Typing is one of the choices rather than a second control next to them. An open question has
    // nothing to choose between, so there the field is the answer and is offered straight away.
    var ownAnswer by mutableStateOf(!hasOptions)

    val answered: Boolean get() = selected.isNotEmpty() || (ownAnswer && typed.isNotBlank())

    /** What this draft sends, or [AskUserController.Answer.Unanswered] when it was left blank. */
    fun answerFor(request: AskUserController.Request): AskUserController.Answer =
        if (!answered) {
            AskUserController.Answer.Unanswered
        } else {
            AskUserController.Answer(
                choices = request.options.filter { it in selected },
                text = typed.trim().takeIf { ownAnswer && it.isNotEmpty() },
                answered = true,
            )
        }
}

/**
 * Drafts for every waiting question, keyed by request id. The chat screen holds one instance for
 * all conversations, so a draft survives switching away and back; [Saver] lets it survive a
 * configuration change too. A new request never inherits a draft.
 */
internal class QuestionDrafts {
    private val drafts = HashMap<Long, QuestionDraft>()

    fun of(request: AskUserController.Request): QuestionDraft =
        drafts.getOrPut(request.id) { QuestionDraft(request.options.isNotEmpty()) }

    /** Forgets answered, skipped and withdrawn questions so their ids cannot come back filled. */
    fun retainOnly(requests: List<AskUserController.Request>) {
        val live = requests.mapTo(HashSet()) { it.id }
        drafts.keys.retainAll(live)
    }

    companion object {
        // Flat so every value is a plain Bundle type: id, ownAnswer, typed, count, then the picks.
        val Saver: Saver<QuestionDrafts, Any> = Saver(
            save = { state ->
                ArrayList<Any>().apply {
                    state.drafts.forEach { (id, draft) ->
                        add(id)
                        add(draft.ownAnswer)
                        add(draft.typed)
                        add(draft.selected.size)
                        addAll(draft.selected)
                    }
                }
            },
            restore = { saved ->
                QuestionDrafts().apply {
                    val values = saved as List<*>
                    var i = 0
                    while (i < values.size) {
                        val draft = QuestionDraft(hasOptions = true)
                        val id = values[i++] as Long
                        draft.ownAnswer = values[i++] as Boolean
                        draft.typed = values[i++] as String
                        val count = values[i++] as Int
                        draft.selected = values.subList(i, i + count).mapTo(LinkedHashSet()) { it as String }
                        i += count
                        drafts[id] = draft
                    }
                }
            },
        )
    }
}

/**
 * The sliding content of one question page: the question, its options and the typed answer.
 *
 * Options are optional: a question without them is an open question. Even with options the user can
 * type instead, because the model's list is its guess at what the answers are, and a wrong guess must
 * not force the user to pick one of it.
 */
@Composable
internal fun QuestionBody(
    request: AskUserController.Request,
    draft: QuestionDraft,
    textFieldModifier: @Composable () -> Modifier = { Modifier },
    prepareTextInput: suspend () -> Unit = {},
    enabled: Boolean = true,
) {
    QuestionPage(request = request, draft = draft, textFieldModifier = textFieldModifier,
        prepareTextInput = prepareTextInput, enabled = enabled)
}

/**
 * The fixed button row under the question pages.
 *
 * Every waiting question shares the card: Back and Next move between pages, and Send on the last
 * page hands all of them over in one [onSubmit], marking any left blank. Skip declines every
 * question on the card.
 */
@Composable
internal fun QuestionActions(
    requests: List<AskUserController.Request>,
    draftOf: (AskUserController.Request) -> QuestionDraft,
    onBack: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onSubmit: (List<Pair<Long, AskUserController.Answer>>) -> Unit,
    onSkip: (Long) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { if (enabled) requests.forEach { onSkip(it.id) } }, enabled = enabled) {
            Text(stringResource(R.string.ask_user_skip))
        }
        Spacer(Modifier.weight(1f))
        onBack?.let {
            TextButton(onClick = { if (enabled) it() }, enabled = enabled) { Text(stringResource(R.string.back)) }
            Spacer(Modifier.width(4.dp))
        }
        if (onNext != null) {
            Button(onClick = { if (enabled) onNext() }, enabled = enabled) { Text(stringResource(R.string.ask_user_next)) }
        } else {
            Button(
                // A question left blank on an earlier page is marked unanswered, not invented.
                onClick = { if (enabled) onSubmit(requests.map { it.id to draftOf(it).answerFor(it) }) },
                enabled = enabled && requests.any { draftOf(it).answered },
            ) { Text(stringResource(R.string.ask_user_send)) }
        }
    }
}

@Composable
private fun QuestionPage(
    request: AskUserController.Request,
    draft: QuestionDraft,
    textFieldModifier: @Composable () -> Modifier,
    prepareTextInput: suspend () -> Unit,
    enabled: Boolean,
) {
    val hasOptions = request.options.isNotEmpty()
    val motion = LocalAgoraMotionPolicy.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Choosing to type opens the field inside the scrolling content and focuses it for the
    // keyboard. [revealed] marks that choice; the field is scrolled into view once it has finished
    // expanding, because while it grows its bounds are still too small to scroll to.
    val field = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    var revealed by remember { mutableStateOf(false) }
    var requestInputFocus by remember { mutableStateOf(false) }
    val latestPrepareTextInput by rememberUpdatedState(prepareTextInput)
    LaunchedEffect(requestInputFocus, enabled) {
        if (requestInputFocus && enabled) {
            latestPrepareTextInput()
            focus.requestFocus()
            requestInputFocus = false
        } else if (!enabled) {
            requestInputFocus = false
        }
    }
    fun setOwnAnswer(on: Boolean) {
        if (on == draft.ownAnswer) return
        draft.ownAnswer = on
        revealed = on
        requestInputFocus = on
        if (!on) {
            // The field is leaving, so it must not keep the keyboard up behind it.
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ScrollableContentMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        // Only the question is selectable (long-press, like message bubbles); options stay tap targets.
        NoAutoScrollSelectionContainer(enabled = enabled) {
            Text(
                text = request.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (hasOptions) {
            Spacer(Modifier.height(10.dp))
            request.options.forEach { option ->
                OptionRow(
                    option = option,
                    checked = option in draft.selected,
                    allowMultiple = request.allowMultiple,
                    enabled = enabled,
                    onToggle = {
                        draft.selected = when {
                            !request.allowMultiple -> setOf(option)
                            option in draft.selected -> draft.selected - option
                            else -> draft.selected + option
                        }
                        // One answer means one choice: picking a listed option puts the typed one
                        // away, and picking the typed one clears the list.
                        if (!request.allowMultiple) setOwnAnswer(false)
                    },
                )
            }
            OptionRow(
                option = stringResource(R.string.ask_user_custom_answer),
                checked = draft.ownAnswer,
                allowMultiple = request.allowMultiple,
                enabled = enabled,
                onToggle = {
                    setOwnAnswer(if (request.allowMultiple) !draft.ownAnswer else true)
                    if (!request.allowMultiple) draft.selected = emptySet()
                },
            )
        }
        val spec = tween<Float>(FieldDurationMs)
        val sizeSpec = tween<IntSize>(FieldDurationMs)
        AnimatedVisibility(
            visible = draft.ownAnswer,
            enter = if (motion.allowContinuousMotion) {
                expandVertically(sizeSpec) + fadeIn(spec)
            } else {
                EnterTransition.None
            },
            exit = if (motion.allowContinuousMotion) {
                shrinkVertically(sizeSpec) + fadeOut(spec)
            } else {
                ExitTransition.None
            },
        ) {
            val expanded = transition.currentState == EnterExitState.Visible &&
                transition.targetState == EnterExitState.Visible
            LaunchedEffect(expanded, revealed) {
                if (expanded && revealed) {
                    field.bringIntoView()
                    revealed = false
                }
            }
            Column {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.typed,
                    onValueChange = { if (enabled) draft.typed = it },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(textFieldModifier())
                        .focusRequester(focus)
                        .bringIntoViewRequester(field),
                    placeholder = if (hasOptions) {
                        null
                    } else {
                        { Text(stringResource(R.string.ask_user_custom_answer)) }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 4,
                )
            }
        }
    }
}

/**
 * A single-line option reads as a capsule; a taller, wrapped option keeps a 24 dp corner so its
 * highlight does not turn into a lozenge.
 */
internal val OptionShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = minOf(size.height / 2f, with(density) { OptionMaxCornerRadius.toPx() })
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius, radius)))
    }
}

private val OptionMaxCornerRadius = 24.dp

@Composable
private fun OptionRow(
    option: String,
    checked: Boolean,
    allowMultiple: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OptionShape)
            .clickable(enabled = enabled, onClick = { if (enabled) onToggle() })
            // Inset so the rounded highlight never cuts into the control.
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row owns the click, so the control itself stays non-interactive and the whole row
        // reads as one target for accessibility services.
        if (allowMultiple) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        } else {
            RadioButton(selected = checked, onClick = null, enabled = enabled)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = option,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
