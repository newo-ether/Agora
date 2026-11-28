package com.newoether.agora.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxDistributionTest {
    @Test
    fun existingOrUnknownSelectionKeepsAlpine() {
        assertEquals(SandboxDistribution.ALPINE, SandboxDistribution.fromId(null))
        assertEquals(SandboxDistribution.ALPINE, SandboxDistribution.fromId(""))
        assertEquals(SandboxDistribution.ALPINE, SandboxDistribution.fromId("missing"))
        SandboxDistribution.entries.forEach { distribution ->
            assertEquals(distribution, SandboxDistribution.fromId(distribution.id))
        }
    }

    @Test
    fun packageShortcutsMatchEachPackageManager() {
        assertFalse(SandboxDistribution.ALPINE.usesApt)
        assertTrue(SandboxDistribution.ALPINE.quickPackages.contains("py3-pip"))
        assertTrue(SandboxDistribution.ALPINE.quickPackages.contains("build-base"))
        listOf(SandboxDistribution.DEBIAN, SandboxDistribution.UBUNTU).forEach { distribution ->
            assertTrue(distribution.usesApt)
            assertTrue(distribution.quickPackages.contains("python3-pip"))
            assertTrue(distribution.quickPackages.contains("build-essential"))
            assertFalse(distribution.quickPackages.contains("build-base"))
        }
    }
}
