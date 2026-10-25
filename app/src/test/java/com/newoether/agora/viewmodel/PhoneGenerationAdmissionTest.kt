package com.newoether.agora.viewmodel

import com.newoether.agora.phone.PhoneUseSettings
import com.newoether.agora.phone.phoneDefinitions
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhoneGenerationAdmissionTest {
    @Test fun admissionAwaitsSettingsAndFreezesModeReadinessAndAutomation() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false, phoneReady = { true })
        val configuration = MutableStateFlow(PhoneUseSettings())
        every { fixture.settings.phoneUse } returns configuration
        val entered = CompletableDeferred<Unit>()
        val loaded = CompletableDeferred<Unit>()
        coEvery { fixture.settings.awaitInitialLoad() } coAnswers {
            entered.complete(Unit)
            loaded.await()
        }
        val admission = async { fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId, automationCaller = true) }
        entered.await()
        assertFalse(admission.isCompleted)
        configuration.value = PhoneUseSettings(enabled = true, mode = "shizuku", allowAutomation = true)
        loaded.complete(Unit)
        val frozen = admission.await().context
        assertTrue(frozen.phoneEnabled)
        assertTrue(frozen.phoneReady)
        assertTrue(frozen.automationCaller)
        assertTrue(frozen.phoneAllowAutomation)
        assertEquals("shizuku", frozen.phoneMode)
        assertEquals(11, phoneDefinitions(frozen).size)
        configuration.value = PhoneUseSettings()
        assertEquals(11, phoneDefinitions(frozen).size)
        coEvery { fixture.settings.awaitInitialLoad() } returns Unit
        assertTrue(phoneDefinitions(fixture.builder.captureAdmissionSnapshot("conversation", "next", fixture.modelId).context).isEmpty())
    }

    @Test fun automationRequiresOptInAndReadinessIsCapturedWithoutProbingForDisabledGroup() = runTest {
        var probes = 0
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false, phoneReady = { probes++; true })
        val configuration = MutableStateFlow(PhoneUseSettings())
        every { fixture.settings.phoneUse } returns configuration
        fixture.builder.captureAdmissionSnapshot("conversation", "disabled", fixture.modelId)
        assertEquals(0, probes)
        configuration.value = PhoneUseSettings(enabled = true)
        val interactive = fixture.builder.captureAdmissionSnapshot("conversation", "interactive", fixture.modelId)
        val task = fixture.builder.captureAdmissionSnapshot("conversation", "task", fixture.modelId, automationCaller = true)
        assertEquals(11, phoneDefinitions(interactive.context).size)
        assertEquals(1, probes)
        assertFalse(interactive.automaticCompact.generationContext.phoneEnabled)
        assertTrue(phoneDefinitions(task.context).isEmpty())
        configuration.value = configuration.value.copy(allowAutomation = true)
        val optedIn = fixture.builder.captureAdmissionSnapshot("conversation", "loop", fixture.modelId, automationCaller = true)
        assertEquals(11, phoneDefinitions(optedIn.context).size)
        assertEquals(2, probes)
    }
}
