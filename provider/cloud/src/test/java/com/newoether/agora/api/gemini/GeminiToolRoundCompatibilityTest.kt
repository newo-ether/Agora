package com.newoether.agora.api.gemini

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A thoughtSignature is opaque state signed by the model that emitted it. These tests pin the
 * replay scope: the issuing model behind the issuing provider entry, and nothing else.
 */
class GeminiToolRoundCompatibilityTest {
    private fun round(
        modelName: String?,
        signature: String?,
        signatureProvider: String? = null,
    ) = ChatMessage(
        id = "tool_round",
        text = "",
        participant = Participant.MODEL,
        modelName = modelName,
        segments = listOf(
            MessageSegment(
                type = "tool",
                toolName = "lookup",
                toolArgs = "{}",
                toolCallId = "call-a",
                signature = signature,
                signatureProvider = signatureProvider,
            ),
        ),
    )

    private fun ChatMessage.compatibleFor(targetModel: String, providerName: String) =
        isGeminiToolRoundCompatible(
            targetModel = targetModel,
            targetProviderName = providerName,
            signatureRequired = true,
        )

    @Test
    fun signatureIssuedByTheSameModelBehindTheSameProviderEntryIsReplayed() {
        assertTrue(round("Google:gemini-3-pro", "sig").compatibleFor("gemini-3-pro", "Google"))
        assertTrue(
            round("Google:models/gemini-3-pro", "sig", "Google")
                .compatibleFor("models/gemini-3-pro", "Google"),
        )
    }

    @Test
    fun signatureIssuedByAnotherModelIsNeverReplayed() {
        assertFalse(round("Google:gemini-3-pro", "sig").compatibleFor("gemini-3.5-pro", "Google"))
        assertFalse(round("Google:gemini-3-pro", "sig").compatibleFor("gemini-3-pro", "Relay"))
    }

    @Test
    fun signatureFromAnotherProviderEntryIsNeverReplayed() {
        assertFalse(
            round("Google:gemini-3-pro", "sig", "Google")
                .compatibleFor("gemini-3-pro", "Google Enterprise"),
        )
    }

    @Test
    fun unattributableSignatureIsNeverReplayed() {
        assertFalse(round(null, "sig").compatibleFor("gemini-3-pro", "Google"))
        assertFalse(round("  ", "sig").compatibleFor("gemini-3-pro", "Google"))
    }

    @Test
    fun unsignedRoundStaysCompatibleUntilASignatureIsRequired() {
        assertTrue(
            round("Google:gemini-3-pro", null)
                .isGeminiToolRoundCompatible("gemini-3-pro", "Google", signatureRequired = false),
        )
        assertFalse(
            round("Google:gemini-3-pro", null)
                .isGeminiToolRoundCompatible("gemini-3-pro", "Google", signatureRequired = true),
        )
    }
}
