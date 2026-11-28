package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.SubagentCapsuleDisplayModes
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.horizontalEdgeFade
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun BoxScope.ChatConversationToolsOverlay(
    viewModel: ChatViewModel,
    conversationId: String?,
    autoWrapCodeBlocks: Boolean,
    bottomBarHeight: Dp,
    onHeightChanged: (Float) -> Unit,
    onMessageInsetChanged: (Float) -> Unit = {},
) {
    val todo by viewModel.currentConversationTodo.collectAsState()
    val family by viewModel.currentSubagentFamily.collectAsState()
    val capsuleDisplayMode by viewModel.settings.subagentCapsuleDisplayMode.collectAsState()
    val loaded by viewModel.loadedMessagesConversationId.collectAsState()
    val subagents = family.forSelection(conversationId)
    val scope = rememberCoroutineScope()
    ChatUserInteractionBarHost(viewModel, conversationId, autoWrapCodeBlocks) { waiting, revealKey, expanded, expansion, interaction ->
        ConversationToolsRow(
            conversationId = conversationId,
            items = todo.second.takeIf { todo.first == conversationId }.orEmpty(),
            agents = subagents?.agents.orEmpty(),
            familyId = subagents?.familyId,
            subagentCapsuleDisplayMode = capsuleDisplayMode,
            hasInteraction = waiting,
            contentReady = conversationId == null || loaded == conversationId &&
                todo.first == conversationId && subagents != null,
            interactionRevealKey = revealKey,
            interactionExpanded = expanded,
            interactionExpansion = expansion,
            interaction = interaction,
            onSelect = { id -> scope.launch { viewModel.selectConversation(id) } },
            onHeightChanged = onHeightChanged,
            onMessageInsetChanged = onMessageInsetChanged,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeight),
        )
    }
}

/** One scroll owner; full interaction editors are never disposed by horizontal virtualization. */
@Composable
internal fun ConversationToolsRow(
    conversationId: String?,
    items: List<ConversationTodoItem>,
    agents: List<SubagentSummary>,
    onSelect: (String) -> Unit,
    onHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hasInteraction: Boolean = false,
    familyId: String? = conversationId,
    onMessageInsetChanged: (Float) -> Unit = {},
    interactionRevealKey: Any? = null,
    interactionExpanded: Boolean = false,
    interactionExpansion: Float = 0f,
    contentReady: Boolean = true,
    subagentCapsuleDisplayMode: String = SubagentCapsuleDisplayModes.INDIVIDUAL,
    interaction: @Composable (
        Modifier, (Float) -> Unit, (Float) -> Unit, Boolean, Boolean, (String?, Boolean) -> Unit,
    ) -> Unit = { _, _, _, _, _, _ -> },
) {
    val motion = LocalAgoraMotionPolicy.current
    val latestHeightChanged by rememberUpdatedState(onHeightChanged)
    val latestMessageInsetChanged by rememberUpdatedState(onMessageInsetChanged)
    val clearance = with(LocalDensity.current) { 8.dp.toPx() }
    var interactionHeight by remember { mutableFloatStateOf(0f) }
    var interactionInset by remember { mutableFloatStateOf(0f) }
    var todoHeight by remember { mutableFloatStateOf(0f) }
    var todoInset by remember { mutableFloatStateOf(0f) }
    var todoPresent by remember { mutableStateOf(false) }
    var todoReady by remember { mutableStateOf<Pair<String?, Boolean>>(null to false) }
    var interactionReady by remember { mutableStateOf(false) }
    var interactionTransition by remember { mutableStateOf<Pair<String?, Boolean>>(null to false) }
    var todoTransition by remember { mutableStateOf<Pair<String?, Boolean>>(null to false) }
    val destination = conversationId to familyId
    var layoutOwner by remember { mutableStateOf(destination) }
    var enteringRow by remember { mutableStateOf(true) }
    val rowProgress = remember { Animatable(0f) }
    val retainingFamily = layoutOwner.second != null && layoutOwner.second == familyId
    val replacingOwner = layoutOwner != destination && !retainingFamily
    SideEffect { if (retainingFamily) layoutOwner = destination }
    LaunchedEffect(destination) {
        if (replacingOwner) enteringRow = true
    }
    val latestEnteringRow by rememberUpdatedState(enteringRow)
    val latestReplacingOwner by rememberUpdatedState(replacingOwner)
    val desired = (familyId to agents).takeIf { familyId != null && agents.isNotEmpty() }
    val grouped = SubagentCapsuleDisplayModes.normalize(subagentCapsuleDisplayMode) ==
        SubagentCapsuleDisplayModes.GROUPED
    val latestGrouped by rememberUpdatedState(grouped)
    var shownAgents by remember { mutableStateOf(desired) }
    var shownGrouped by remember { mutableStateOf(grouped) }
    val latestDesired by rememberUpdatedState(desired)
    val agentProgress = remember { Animatable(0f) }
    val active = desired != null && shownAgents?.first == familyId && shownGrouped == grouped
    val displayedAgents = if (active && shownGrouped) agents else shownAgents?.second.orEmpty().map { old ->
        if (active) agents.firstOrNull { it.id == old.id } ?: old else old
    }
    val heights = remember { mutableStateMapOf<String, Float>() }
    val agentProgresses = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val occupiedWidths = remember { mutableStateMapOf<String, Int>() }
    val agentReady = remember { mutableStateMapOf<String, Boolean>() }
    SideEffect {
        if (active) {
            shownAgents = familyId to if (shownGrouped) agents else
                retainLeavingSubagents(shownAgents?.second.orEmpty(), agents)
        }
    }
    val agentKeys = if (shownGrouped && displayedAgents.isNotEmpty()) {
        listOf("subagents:${shownAgents?.first}")
    } else displayedAgents.map { "subagent:${shownAgents?.first}:${it.id}" }
    // Initial paint is admitted as one row after every loaded tool has reserved its width.
    val appearanceReady = !replacingOwner && contentReady &&
        (!hasInteraction || interactionReady) &&
        (items.isEmpty() || todoReady == (conversationId to true)) &&
        (desired == null || active && if (grouped) agentReady["subagents:$familyId"] == true
        else agents.all { agentReady["subagent:$familyId:${it.id}"] == true })
    val latestInteractionVisible by rememberUpdatedState(interactionTransition == (conversationId to true))
    val latestTodoVisible by rememberUpdatedState(todoTransition == (conversationId to true))
    val latestHasInteraction by rememberUpdatedState(hasInteraction)
    val latestHasTodo by rememberUpdatedState(items.isNotEmpty())
    val latestAgentKeys by rememberUpdatedState(agentKeys)
    val latestHasAgents by rememberUpdatedState(desired != null)
    val latestAppearanceReady by rememberUpdatedState(appearanceReady)
    LaunchedEffect(familyId, desired != null, grouped) {
        if (shownAgents != null && !active) {
            agentProgress.animateTo(0f, tween(180))
            snapshotFlow { latestReplacingOwner || occupiedWidths.values.all { it == 0 } }.first { it }
            shownAgents = null
        }
        latestDesired?.let {
            shownGrouped = latestGrouped
            shownAgents = it
            snapshotFlow { latestAppearanceReady }.first { it }
            if (latestEnteringRow) agentProgress.snapTo(1f)
            else agentProgress.animateTo(1f, tween(180))
        }
    }
    val sheetFamily = familyId.takeIf { grouped && active && agents.size >= 2 && contentReady }
    var openedFamily by remember(sheetFamily) { mutableStateOf<String?>(null) }
    val interactionPresent = hasInteraction || interactionHeight > 0f
    val todoVisible = conversationId != null && items.isNotEmpty() || todoPresent
    val keys = buildList {
        if (interactionPresent) add("interaction")
        if (todoVisible) add("todo")
        addAll(agentKeys)
    }
    LaunchedEffect(motion.allowSpatialTransitions, clearance) {
        snapshotFlow {
            var agentHeight = 0f
            var agentInset = 0f
            shownAgents?.let { (owner, children) ->
                val entries = if (shownGrouped) listOf("subagents:$owner" to null) else
                    children.map { "subagent:$owner:${it.id}" to it.id }
                entries.forEach { (entryKey, childId) ->
                    val height = heights[entryKey] ?: 0f
                    val present = latestDesired?.first == owner && latestGrouped == shownGrouped &&
                        (shownGrouped || latestDesired?.second.orEmpty().any { it.id == childId })
                    val fraction = if (motion.allowSpatialTransitions) {
                        minOf(agentProgress.value, agentProgresses[entryKey]?.value ?: 0f)
                    } else if (present) 1f else 0f
                    agentHeight = maxOf(agentHeight, height * fraction)
                    if (height > 0f) agentInset = maxOf(agentInset, (height + clearance) * fraction)
                }
            }
            val fraction = if (motion.allowSpatialTransitions) rowProgress.value else 1f
            maxOf(interactionHeight, todoHeight, agentHeight) * fraction to
                maxOf(interactionInset, todoInset, agentInset) * fraction
        }.collect { (height, inset) ->
            latestHeightChanged(height)
            latestMessageInsetChanged(inset)
        }
    }
    DisposableEffect(Unit) {
        onDispose { latestHeightChanged(0f); latestMessageInsetChanged(0f) }
    }
    SideEffect {
        heights.keys.retainAll(agentKeys.toSet())
        agentProgresses.keys.retainAll(agentKeys.toSet())
        occupiedWidths.keys.retainAll(agentKeys.toSet())
        agentReady.keys.retainAll(agentKeys.toSet())
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val itemMaxWidth = (maxWidth - 24.dp).coerceAtLeast(48.dp)
        val gap = with(density) { 8.dp.roundToPx() }
        val minimumTrackWidth = with(density) { (maxWidth - 24.dp).roundToPx() }
        val scroll = rememberScrollState()
        val startFade by animateFloatAsState(if (!interactionExpanded && scroll.canScrollBackward) 1f else 0f,
            tween(200, easing = FastOutSlowInEasing), label = "tools-start-fade")
        val endFade by animateFloatAsState(if (!interactionExpanded && scroll.canScrollForward) 1f else 0f,
            tween(200, easing = FastOutSlowInEasing), label = "tools-end-fade")
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val dragged by scroll.interactionSource.collectIsDraggedAsState()
        val widths = remember { mutableStateMapOf<String, Int>() }
        val occupied = remember { mutableStateMapOf<String, Int>() }
        val slotKeys = listOf("interaction", "todo") + agentKeys
        SideEffect {
            widths.keys.retainAll(slotKeys.toSet())
            occupied.keys.retainAll(slotKeys.toSet())
        }
        val targets = slotKeys.map { it to (widths[it] ?: 0) }
        val outgoingVisible = interactionTransition.let { (owner, visible) ->
            visible && owner != conversationId
        } || todoTransition.let { (owner, visible) ->
            visible && owner != conversationId
        } || shownAgents?.let { (owner, _) -> owner != familyId && agentProgress.value > 0f } == true
        val snapLayout = replacingOwner && !outgoingVisible
        // Exiting Interaction/Todo slots still occupy width until their collapse settles.
        val geometry = slotKeys.map { it to (occupied[it] ?: 0) }.filter { it.second > 0 }
        var previousGeometry by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
        var anchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
        // User cancellation belongs only to this width change, not a later restored layout.
        var cancelledTargets by remember(targets) { mutableStateOf<List<Pair<String, Int>>?>(null) }
        var revealingInteraction by remember { mutableStateOf(false) }
        var handledRevealKey by remember { mutableStateOf<Any?>(null) }
        var revealTargets by remember(conversationId) { mutableStateOf<List<Pair<String, Int>>?>(null) }
        SideEffect {
            if (!revealingInteraction && revealTargets != targets) revealTargets = null
        }
        val latestTargets by rememberUpdatedState(targets)
        val latestGeometry by rememberUpdatedState(geometry)
        val latestLayoutReady by rememberUpdatedState(
            (!hasInteraction || interactionTransition.first == conversationId && interactionReady) &&
                (items.isEmpty() || todoReady == (conversationId to true)) &&
                (desired == null || shownAgents?.first == familyId) &&
                (interactionTransition.first == null || interactionTransition.first == conversationId) &&
                (todoTransition.first == null || todoTransition.first == conversationId) &&
                (shownAgents == null || shownAgents?.first == familyId) &&
                slotKeys.all { (occupied[it] ?: 0) == (widths[it] ?: 0) },
        )
        LaunchedEffect(destination, snapLayout) {
            if (!snapLayout) return@LaunchedEffect
            // The outgoing row keeps its shared paint and lift until its own exit is invisible.
            rowProgress.snapTo(0f)
            // Reset the unrelated family's viewport only after every old capsule is invisible.
            scroll.scrollTo(0)
            snapshotFlow { latestLayoutReady }.first { it }
            // Layout and ScrollState must publish the destination range before admitting a fade.
            withFrameNanos { }
            snapshotFlow { latestLayoutReady }.first { it }
            scroll.scrollTo(0)
            previousGeometry = latestGeometry
            anchor = null
            cancelledTargets = null
            revealTargets = latestTargets
            layoutOwner = destination
        }
        LaunchedEffect(interactionRevealKey, dragged, replacingOwner, interactionExpanded, destination) {
            if (replacingOwner) return@LaunchedEffect
            val event = interactionRevealKey
            if (!interactionExpanded && (event == null || event == handledRevealKey)) return@LaunchedEffect
            handledRevealKey = event
            if (dragged) return@LaunchedEffect
            revealingInteraction = true
            try {
                if (motion.allowSpatialTransitions) {
                    scroll.animateScrollTo(0, tween(350, easing = FastOutSlowInEasing))
                } else scroll.scrollTo(0)
            } finally {
                // Suppress only this structural wave; later unrelated insertions may anchor again.
                revealTargets = latestTargets
                revealingInteraction = false
            }
        }
        LaunchedEffect(geometry, dragged, scroll.value, revealingInteraction, replacingOwner) {
            if (replacingOwner) {
                previousGeometry = null
                anchor = null
                return@LaunchedEffect
            }
            val before = previousGeometry
            previousGeometry = geometry
            if (dragged) cancelledTargets = targets
            if (before == null || before == geometry || dragged || cancelledTargets == targets ||
                revealingInteraction || interactionExpanded || interactionExpansion > 0f || revealTargets == targets ||
                interactionRevealKey != null && interactionRevealKey != handledRevealKey) {
                anchor = sharedToolsAnchor(geometry.map { it.first }, geometry.toMap(), scroll.value)
                return@LaunchedEffect
            }
            val previousKeys = before.map { it.first }
            val nextKeys = geometry.map { it.first }
            val maximum = (geometry.sumOf { it.second } - minimumTrackWidth).coerceAtLeast(0)
            val target = sharedToolsScrollTarget(anchor, previousKeys, nextKeys, geometry.toMap(), scroll.value, maximum)
            // Follow the animated occupied widths frame by frame, rather than starting a second
            // travel tween that could race the changing scroll range. User drag cancels this wave.
            scroll.scrollTo(target)
            anchor = sharedToolsAnchor(nextKeys, geometry.toMap(), target)
        }
        var placedTrackWidth by remember { mutableStateOf(0) }
        var placedTrackHeight by remember { mutableStateOf(0) }
        var placedTrackOffset by remember { mutableStateOf(0) }
        LaunchedEffect(destination, replacingOwner, enteringRow) {
            if (replacingOwner || !enteringRow) return@LaunchedEffect
            snapshotFlow {
                latestAppearanceReady && latestLayoutReady &&
                    (!latestHasInteraction || latestInteractionVisible && interactionHeight > 0f) &&
                    (!latestHasTodo || latestTodoVisible) &&
                    (!latestHasAgents || agentProgress.value == 1f && latestAgentKeys.all {
                        agentProgresses[it]?.value == 1f
                    })
            }.first { it }
            // Admit only a placed, unchanged track after every child's own entry has finished hidden.
            var geometry = latestGeometry
            var settled: Boolean
            do {
                withFrameNanos { }
                settled = geometry == latestGeometry && latestAppearanceReady && latestLayoutReady
                geometry = latestGeometry
            } while (!settled)
            if (!latestHasInteraction && !latestHasTodo && !latestHasAgents) rowProgress.snapTo(1f)
            else rowProgress.animateTo(1f, tween(180))
            enteringRow = false
        }
        Row(
            modifier = Modifier.testTag("conversation-tools-row")
                .horizontalEdgeFade(
                    leftWeight = if (rtl) endFade else startFade,
                    rightWeight = if (rtl) startFade else endFade,
                )
                .horizontalScroll(scroll, enabled = !interactionExpanded).padding(horizontal = 12.dp)
                .graphicsLayer {
                    alpha = rowProgress.value
                    val scale = if (motion.allowSpatialTransitions) 0.96f + 0.04f * alpha else 1f
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val holdOutgoingLayout = replacingOwner && !snapLayout
                    val centeredInteraction = with(density) {
                        ((maxWidth - 24.dp - minOf(itemMaxWidth, 840.dp)).coerceAtLeast(0.dp).toPx() *
                            interactionExpansion / 2f).toInt()
                    }
                    val width = if (holdOutgoingLayout) placedTrackWidth else
                        maxOf(placeable.width + centeredInteraction, minimumTrackWidth)
                    val height = if (holdOutgoingLayout) placedTrackHeight else placeable.height
                    val offset = if (holdOutgoingLayout) placedTrackOffset else {
                        // Center the occupied track, including prefix widths still entering or leaving.
                        val fittedOffset = (minimumTrackWidth - placeable.width).coerceAtLeast(0) / 2
                        centeredInteraction + (fittedOffset * (1f - interactionExpansion)).toInt()
                    }
                    if (!holdOutgoingLayout) {
                        placedTrackWidth = width
                        placedTrackHeight = height
                        placedTrackOffset = offset
                    }
                    val measuredGeometry = slotKeys.map { it to (occupied[it] ?: 0) }.filter { it.second > 0 }
                    val before = previousGeometry
                    val compensateAnchor = !replacingOwner && !dragged && !revealingInteraction &&
                        !interactionExpanded && interactionExpansion == 0f &&
                        cancelledTargets != targets && revealTargets != targets &&
                        (interactionRevealKey == null || interactionRevealKey == handledRevealKey) &&
                        before != null && before != measuredGeometry
                    // The outer scroll modifier clamps its offset after measuring this track.
                    // Read the accepted offset during placement so range shrinkage is not applied twice.
                    val anchorTarget = if (compensateAnchor) sharedToolsScrollTarget(
                        anchor, before.orEmpty().map { it.first }, measuredGeometry.map { it.first },
                        measuredGeometry.toMap(), scroll.value,
                        (measuredGeometry.sumOf { it.second } - minimumTrackWidth).coerceAtLeast(0),
                    ) else null
                    layout(width, height) {
                        val anchorOffset = anchorTarget?.let { scroll.value - it } ?: 0
                        placeable.placeRelative(offset + anchorOffset, height - placeable.height)
                    }
                },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Start,
        ) {
            // These two slots stay composed while empty so the card keeps its own exit/page state.
            SharedToolSlot("interaction", interactionPresent && keys.lastOrNull() != "interaction", gap,
                { widths["interaction"] = it }, visible = interactionPresent,
                followContentWidth = true, contentVisible = interactionTransition.second,
                freezeWidth = replacingOwner && !snapLayout, snapWidth = snapLayout || enteringRow && !replacingOwner,
                onReadyChanged = { interactionReady = it },
                onOccupiedWidth = { occupied["interaction"] = it }) { slot ->
                interaction(slot.widthIn(max = itemMaxWidth), { interactionHeight = it }, { interactionInset = it },
                    interactionReady && appearanceReady, !enteringRow,
                    { owner, visible -> interactionTransition = owner to visible })
            }
            val measuredTodoOwner = todoTransition.first
            SharedToolSlot("todo", todoVisible && keys.lastOrNull() != "todo", gap,
                { widths["todo"] = it }, visible = todoVisible, preserveContentWidth = true,
                contentVisible = todoPresent,
                freezeWidth = replacingOwner && !snapLayout, snapWidth = snapLayout || enteringRow && !replacingOwner,
                onReadyChanged = { todoReady = measuredTodoOwner to it },
                onOccupiedWidth = { occupied["todo"] = it }) { slot ->
                ConversationTodoBar(
                    conversationId, items, { todoHeight = it }, slot.widthIn(max = itemMaxWidth),
                    inSharedRow = true, onPresenceChanged = { todoPresent = it },
                    onMessageInsetChanged = { todoInset = it }, appearanceReady = appearanceReady,
                    animateEntry = !enteringRow,
                    onTransitionChanged = { owner, visible -> todoTransition = owner to visible },
                )
            }
            displayedAgents.take(agentKeys.size).forEachIndexed { index, agent ->
                val entryKey = agentKeys[index]
                key(entryKey) {
                    val present = active && (shownGrouped || agents.any { it.id == agent.id })
                    val progress = remember { Animatable(0f) }
                    var ready by remember { mutableStateOf(false) }
                    SideEffect { agentProgresses[entryKey] = progress }
                    LaunchedEffect(present, ready, appearanceReady) {
                        if (present && (!ready || !appearanceReady && progress.value == 0f)) return@LaunchedEffect
                        if (present && enteringRow) progress.snapTo(1f)
                        else progress.animateTo(if (present) 1f else 0f, tween(180))
                        if (!present && active) {
                            snapshotFlow { occupiedWidths[entryKey] == 0 }.first { it }
                            if (latestDesired?.first == shownAgents?.first &&
                                latestDesired?.second.orEmpty().none { it.id == agent.id }) {
                                shownAgents = shownAgents?.let { (owner, children) ->
                                    owner to children.filterNot { it.id == agent.id }
                                }
                            }
                        }
                    }
                    SharedToolSlot(entryKey, index < agentKeys.lastIndex, gap,
                        { widths[entryKey] = it }, visible = present, preserveContentWidth = true,
                        contentVisible = minOf(agentProgress.value, progress.value) > 0f,
                        followContentWidth = true,
                        freezeWidth = replacingOwner && !snapLayout, snapWidth = snapLayout || enteringRow && !replacingOwner,
                        onReadyChanged = { ready = it; agentReady[entryKey] = it },
                        onOccupiedWidth = { width ->
                            occupiedWidths[entryKey] = width
                            occupied[entryKey] = width
                        },
                    ) { slot ->
                        Box(slot.onSizeChanged { heights[entryKey] = it.height.toFloat() }) {
                            val capsuleModifier = Modifier.padding(bottom = 8.dp).graphicsLayer {
                                alpha = minOf(agentProgress.value, progress.value)
                                val scale = if (motion.allowSpatialTransitions) 0.96f + 0.04f * alpha else 1f
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                            if (shownGrouped) {
                                ConversationGroupedSubagentCapsule(
                                    displayedAgents, conversationId, present && (ready || progress.value > 0f), capsuleModifier,
                                    onSelect = onSelect,
                                    onOpen = { if (sheetFamily != null) openedFamily = sheetFamily },
                                )
                            } else {
                                ConversationSubagentCapsule(
                                    agent, selected = agent.id == conversationId, active = present && ready,
                                    onSelect = onSelect, modifier = capsuleModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (sheetFamily != null && openedFamily == sheetFamily) {
        ConversationSubagentsSheet(
            agents = agents, conversationId = conversationId,
            onDismiss = { openedFamily = null },
            onSelect = onSelect,
        )
    }
}

/** Compact content keeps its natural layout; reserve before fade-in and release after fade-out. */
@Composable
internal fun SharedToolSlot(
    key: String,
    trailingGap: Boolean,
    gap: Int,
    onWidth: (Int) -> Unit,
    visible: Boolean = true,
    preserveContentWidth: Boolean = false,
    contentVisible: Boolean = false,
    onReadyChanged: (Boolean) -> Unit = {},
    onOccupiedWidth: (Int) -> Unit = {},
    freezeWidth: Boolean = false,
    snapWidth: Boolean = false,
    followContentWidth: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    val motion = LocalAgoraMotionPolicy.current
    var targetWidth by remember { mutableStateOf(0) }
    var naturalWidth by remember { mutableStateOf(0) }
    // Rebase the existing animator while invisible; it must not replay a stale tween on release.
    // The visible card owns its rounded width morph; reserve space before admitting new paint.
    val followMeasuredWidth = followContentWidth && contentVisible
    val animatedWidth = key(snapWidth, followContentWidth) {
        animateIntAsState(targetWidth,
            if (motion.allowSpatialTransitions && !snapWidth && !followMeasuredWidth) tween(320) else snap(), label = "tools-slot-width")
    }
    var lastOccupiedWidth by remember { mutableStateOf(0) }
    val occupiedWidth = when {
        freezeWidth -> lastOccupiedWidth
        snapWidth || followMeasuredWidth || !motion.allowSpatialTransitions -> targetWidth
        else -> animatedWidth.value
    }
    Box(Modifier.testTag("tools-slot:$key").graphicsLayer {
        clip = if (preserveContentWidth || followContentWidth) occupiedWidth == 0 else size.width < naturalWidth
    }.layout { measurable, constraints ->
        // Measure the actual emitted tree, including the empty tree after a retained card exits.
        // The child never receives an animated width and cannot leave an intrinsic-width ghost.
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        naturalWidth = placeable.width + if (trailingGap && placeable.width > 0) gap else 0
        targetWidth = if (visible || (preserveContentWidth || followContentWidth) && contentVisible) naturalWidth else 0
        onWidth(if (visible) naturalWidth else 0)
        val currentWidth = when {
            freezeWidth -> lastOccupiedWidth
            snapWidth || followMeasuredWidth || !motion.allowSpatialTransitions -> targetWidth
            else -> animatedWidth.value
        }
        lastOccupiedWidth = currentWidth
        onReadyChanged(visible && naturalWidth > 0 && currentWidth >= naturalWidth)
        onOccupiedWidth(currentWidth)
        layout(currentWidth, placeable.height) { placeable.placeRelative(0, 0) }
    }) {
        content(Modifier)
    }
}

internal fun retainLeavingSubagents(
    shown: List<SubagentSummary>,
    desired: List<SubagentSummary>,
): List<SubagentSummary> {
    val retained = desired.toMutableList()
    shown.forEachIndexed { index, agent ->
        if (desired.none { it.id == agent.id }) {
            val next = shown.drop(index + 1).firstOrNull { old -> retained.any { it.id == old.id } }
            val position = retained.indexOfFirst { it.id == next?.id }.takeIf { it >= 0 } ?: retained.size
            retained.add(position, agent)
        }
    }
    return retained
}

internal fun sharedToolsAnchor(keys: List<String>, widths: Map<String, Int>, scroll: Int): Pair<String, Int>? {
    var start = 0
    keys.forEach { key ->
        val width = widths[key] ?: 0
        if (start + width > scroll) return key to scroll - start
        start += width
    }
    return keys.lastOrNull()?.let { it to 0 }
}

internal fun sharedToolsScrollTarget(
    anchor: Pair<String, Int>?,
    before: List<String>,
    after: List<String>,
    widths: Map<String, Int>,
    current: Int,
    maximum: Int,
): Int {
    if (current == 0) return 0
    if (anchor == null || after.isEmpty()) return current.coerceIn(0, maximum)
    val key = anchor.first.takeIf { it in after } ?: before.drop(before.indexOf(anchor.first) + 1)
        .firstOrNull { it in after } ?: after.last()
    val start = after.takeWhile { it != key }.sumOf { widths[it] ?: 0 }
    val offset = if (key == anchor.first) anchor.second.coerceAtMost((widths[key] ?: 0).coerceAtLeast(1) - 1) else 0
    return (start + offset).coerceIn(0, maximum)
}
