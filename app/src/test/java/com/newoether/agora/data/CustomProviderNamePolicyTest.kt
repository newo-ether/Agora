package com.newoether.agora.data

import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderNamePolicyTest {
    @Test
    fun builtInNamesAreReservedCaseInsensitively() {
        val builtInNames = listOf(
            Constants.PROVIDER_GOOGLE,
            Constants.PROVIDER_OPENAI,
            Constants.PROVIDER_ANTHROPIC,
            Constants.PROVIDER_DEEPSEEK,
            Constants.PROVIDER_QWEN,
            Constants.PROVIDER_GROQ,
            Constants.PROVIDER_OLLAMA,
            Constants.PROVIDER_OPEN_ROUTER,
            Constants.PROVIDER_OPENCODE_GO,
            Constants.PROVIDER_LOCAL,
        )

        builtInNames.forEach { name ->
            assertFalse(name, CustomProviderNamePolicy.isAllowed(name))
            assertFalse(name.lowercase(), CustomProviderNamePolicy.isAllowed(name.lowercase()))
        }
    }

    @Test
    fun blankAndCaseInsensitiveDuplicateNamesConflict() {
        assertTrue(
            CustomProviderNamePolicy.hasConflict(
                name = " ",
                existingNames = emptyList(),
            ),
        )
        assertTrue(
            CustomProviderNamePolicy.hasConflict(
                name = "example",
                existingNames = listOf("Example"),
            ),
        )
        assertFalse(
            CustomProviderNamePolicy.hasConflict(
                name = "Example Two",
                existingNames = listOf("Example"),
            ),
        )
    }

    @Test
    fun renameCanKeepItsOwnNameOrChangeOnlyItsCase() {
        val existing = listOf("Example", "Other")

        assertFalse(
            CustomProviderNamePolicy.hasConflict(
                name = "Example",
                existingNames = existing,
                currentName = "Example",
            ),
        )
        assertFalse(
            CustomProviderNamePolicy.hasConflict(
                name = "example",
                existingNames = existing,
                currentName = "Example",
            ),
        )
    }

    @Test
    fun sanitizeQuarantinesReservedBlankAndDuplicateConfigs() {
        val result = CustomProviderNamePolicy.sanitize(
            listOf(
                CustomProviderConfig("Example"),
                CustomProviderConfig("Local"),
                CustomProviderConfig("example"),
                CustomProviderConfig(" "),
                CustomProviderConfig("Other"),
            ),
        )

        assertEquals(listOf("Example", "Other"), result.accepted.map { it.name })
        assertEquals(listOf("Local", "example", " "), result.rejected.map { it.name })
    }
}
