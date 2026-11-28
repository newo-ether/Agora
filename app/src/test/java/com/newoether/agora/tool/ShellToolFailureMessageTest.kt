package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellToolFailureMessageTest {

    @Test
    fun `deadline failures tell the user to narrow the scan`() {
        val message = readableFileToolFailure("file_grep", "walk: context deadline exceeded")

        assertEquals(
            "file_grep failed: the server took too long to scan the files. " +
                "Narrow the path or glob, then retry.",
            message,
        )
    }

    @Test
    fun `permission failures point at unreadable trees`() {
        val message = readableFileToolFailure("file_glob", "readdir /root: permission denied")

        assertEquals(
            "file_glob failed: the server was not allowed to read part of that tree. " +
                "Pick a path it can read.",
            message,
        )
    }

    @Test
    fun `unknown failures stay generic and never throw without android logging`() {
        // Plain JVM unit tests have no android.util.Log; the readable sentence must still return.
        val message = readableFileToolFailure("file_read", "unexpected backend panic")

        assertEquals(
            "file_read failed: an internal error interrupted the request. " +
                "Retry once; if it keeps failing, narrow the request.",
            message,
        )
    }

    @Test
    fun `missing detail still returns the generic sentence`() {
        val generic = "file_grep failed: an internal error interrupted the request. " +
            "Retry once; if it keeps failing, narrow the request."

        assertEquals(generic, readableFileToolFailure("file_grep", null))
        assertEquals(generic, readableFileToolFailure("file_grep", "   "))
    }
}
