package com.newoether.agora.automation

import android.content.Context
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.viewmodel.BoundRunGenerationLauncher
import com.newoether.agora.viewmodel.ContextCompactor
import com.newoether.agora.viewmodel.ConversationCompactController
import com.newoether.agora.viewmodel.GenerationFinalizer
import com.newoether.agora.viewmodel.GenerationManager
import com.newoether.agora.viewmodel.GenerationTerminalSettlementController
import com.newoether.agora.viewmodel.RunFinalizationEffectCoordinator
import com.newoether.agora.viewmodel.StandardGenerationContinuationLauncher
import com.newoether.agora.viewmodel.normalizePersistedGenerationErrorText
import com.newoether.agora.viewmodel.toUiChatMessage

/**
 * How a headless run compacts its context and settles a terminal generation.
 *
 * The foreground path builds the same chain out of ChatViewModel, where every step can also reach
 * UI state. A background run has no UI: no snackbars, no open conversation, no graph projection. All
 * of those differences used to sit inline in [TaskExecutionEngine] as a block of collaborator
 * construction; keeping them here makes the headless variant one named thing that the engine simply
 * asks for a compactor, a settlement controller, and a compact controller.
 *
 * [generationManagerProvider] is a provider rather than a value because the manager and this chain
 * refer to each other: the launcher needs the manager that the engine builds after this pipeline.
 */
internal class HeadlessCompactPipeline(
    private val conversations: ConversationRepository,
    private val appContext: Context,
    executionCoordinator: ConversationExecutionCoordinator,
    generationManagerProvider: () -> GenerationManager,
) {
    /** Decides whether a context compact is needed and performs it. */
    val compactor = ContextCompactor(
        conversations = conversations,
        generationErrorFormatter = { raw ->
            normalizePersistedGenerationErrorText(appContext, raw)
        },
    )

    /** Settles a finished or failed run without any UI side effect. */
    val terminalSettlement = GenerationTerminalSettlementController(
        conversations = conversations,
        stopFinalizer = GenerationFinalizer(conversations) { _, _ -> },
        runFinalizationEffects = RunFinalizationEffectCoordinator(),
        failureText = { "Generation failed" },
        toUiMessage = { it.toUiChatMessage(appContext) },
        onSnackbar = {},
    )

    private val boundRunGenerationLauncher = BoundRunGenerationLauncher(
        conversations = conversations,
        generationManagerProvider = generationManagerProvider,
        automaticCompactNeeded = compactor::automaticNeeded,
        terminalSettlement = terminalSettlement,
        toUiMessage = { it.toUiChatMessage(appContext) },
    )

    private val continuationLauncher = StandardGenerationContinuationLauncher(
        conversations = conversations,
        executionCoordinator = executionCoordinator,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = { boundRunGenerationLauncher },
        toUiMessage = { it.toUiChatMessage(appContext) },
        // A headless run never owns the open conversation, and it never projects the message graph
        // into UI state.
        isConversationOpen = { false },
        projectGraph = { _, _, _, _ -> },
    )

    /** Entry point the engine uses to start or continue an automatic compact. */
    val controller = ConversationCompactController(
        conversations = conversations,
        operation = compactor,
        requestBuilder = null,
        generationManagerProvider = generationManagerProvider,
        continuationLauncher = { continuationLauncher },
    )
}
