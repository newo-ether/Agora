package com.newoether.agora.ui.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgoraMotionPolicyTest {
    @Test
    fun fullMotionRemainsAvailableWhenNeitherPreferenceRequestsReduction() {
        val policy = resolveAgoraMotionPolicy(
            appReduceMotion = false,
            systemAnimationsDisabled = false,
        )

        assertFalse(policy.reduceMotion)
        assertTrue(policy.allowContinuousMotion)
        assertTrue(policy.allowSpatialTransitions)
        assertTrue(policy.allowProgrammaticScrollMotion)
    }

    @Test
    fun appPreferenceDisablesMotionSensitiveCapabilities() {
        val policy = resolveAgoraMotionPolicy(
            appReduceMotion = true,
            systemAnimationsDisabled = false,
        )

        assertReduced(policy)
    }

    @Test
    fun systemRemoveAnimationsAlsoDisablesMotionSensitiveCapabilities() {
        val policy = resolveAgoraMotionPolicy(
            appReduceMotion = false,
            systemAnimationsDisabled = true,
        )

        assertReduced(policy)
    }

    @Test
    fun einkDisablesMotionWithoutChangingTheStoredReducedMotionPreference() {
        val enabled = resolveAgoraMotionPolicy(false, false, eInkMode = true)
        assertReduced(enabled)
        assertTrue(enabled.eInkMode)
        val restored = resolveAgoraMotionPolicy(false, false, eInkMode = false)
        assertFalse(restored.reduceMotion)
        assertTrue(restored.allowContinuousMotion)
        assertFalse(resolveAgoraMotionPolicy(true, false).eInkMode)
    }

    private fun assertReduced(policy: AgoraMotionPolicy) {
        assertTrue(policy.reduceMotion)
        assertFalse(policy.allowContinuousMotion)
        assertFalse(policy.allowSpatialTransitions)
        assertFalse(policy.allowProgrammaticScrollMotion)
    }
}
