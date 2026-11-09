package com.newoether.agora.api

import java.io.IOException
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class BoundedWireReadTest {
    @Test fun unknownLengthBodyStopsBeforeStringOrCryptoAllocation() {
        val wire = RepeatingSource()
        wire.buffer().use { input ->
            assertThrows(IOException::class.java) { input.readBoundedWireText(65_536) }
        }
        assertTrue("must not drain an attacker-controlled body", wire.bytesRead <= 65_536 + 8192)
    }

    @Test fun missingNewlineCannotGrowAnUnboundedEventBuffer() {
        val wire = RepeatingSource()
        wire.buffer().use { input ->
            assertThrows(IOException::class.java) { input.readBoundedWireLine(65_536) }
        }
        assertTrue(wire.bytesRead <= 65_536 + 8192)
    }

    @Test fun exactLimitUtf8AndCrLfRemainValid() {
        assertEquals("中文", Buffer().writeUtf8("中文").readBoundedWireText(6))
        val source = Buffer().writeUtf8("中文\r\n")
        assertEquals("中文", source.readBoundedWireLine(6))
        assertNull(source.readBoundedWireLine(6))
    }

    @Test fun partialLineFailsExplicitly() {
        assertThrows(IOException::class.java) { Buffer().writeUtf8("partial").readBoundedWireLine(64) }
    }

    private class RepeatingSource : Source {
        var bytesRead = 0L
        private val block = ByteArray(8192) { 'x'.code.toByte() }
        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = minOf(byteCount, block.size.toLong()).toInt()
            sink.write(block, 0, count)
            bytesRead += count
            return count.toLong()
        }
        override fun timeout() = Timeout.NONE
        override fun close() {}
    }
}
