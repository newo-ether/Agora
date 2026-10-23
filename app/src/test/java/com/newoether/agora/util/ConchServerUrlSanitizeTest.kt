package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ConchServerUrlSanitizeTest {

    @Test
    fun repairsEmbeddedNewlineAndStrayAddress() {
        // The exact broken prod value: a trailing newline + a half-pasted alternate address.
        val raw = "http://10.11.12.1:14216/\n     146.254:14216"
        assertEquals("http://10.11.12.1:14216", sanitizeConchServerUrl(raw))
    }

    @Test
    fun stripsTrailingSlashesAndWhitespace() {
        assertEquals("http://host:14216", sanitizeConchServerUrl("  http://host:14216///  "))
    }

    @Test
    fun leavesCleanUrlUnchanged() {
        assertEquals("http://10.11.12.1:14216", sanitizeConchServerUrl("http://10.11.12.1:14216"))
    }

    @Test
    fun dropsTrailingWhitespaceDelimitedJunkOnSameLine() {
        assertEquals("http://host:14216", sanitizeConchServerUrl("http://host:14216 extra junk"))
    }
}
