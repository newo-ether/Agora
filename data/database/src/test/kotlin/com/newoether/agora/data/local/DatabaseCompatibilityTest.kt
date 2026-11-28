package com.newoether.agora.data.local

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseCompatibilityTest {
    @Test
    fun `missing database is openable without reading a version`() {
        var read = false

        val result = classifyDatabaseCompatibility(
            databaseExists = false,
            currentVersion = 22,
        ) {
            read = true
            99
        }

        assertEquals(DatabaseCompatibility.Missing, result)
        assertFalse(read)
        assertTrue(result.canOpen)
    }

    @Test
    fun `supported and current versions remain openable`() {
        listOf(0, 1, 21, 22).forEach { version ->
            val result = classifyDatabaseCompatibility(
                databaseExists = true,
                currentVersion = 22,
                readStoredVersion = { version },
            )
            assertEquals(DatabaseCompatibility.Supported(version), result)
            assertTrue(result.canOpen)
        }
    }

    @Test
    fun `future version is blocked without changing its value`() {
        val result = classifyDatabaseCompatibility(
            databaseExists = true,
            currentVersion = 22,
            readStoredVersion = { 23 },
        )

        assertEquals(DatabaseCompatibility.FutureVersion(23, 22), result)
        assertFalse(result.canOpen)
    }

    @Test
    fun `version read failure is explicit and fail closed`() {
        val failure = IOException("locked")

        val result = classifyDatabaseCompatibility(
            databaseExists = true,
            currentVersion = 22,
            readStoredVersion = { throw failure },
        )

        assertTrue(result is DatabaseCompatibility.Unreadable)
        assertSame(failure, (result as DatabaseCompatibility.Unreadable).error)
        assertFalse(result.canOpen)
    }
}
