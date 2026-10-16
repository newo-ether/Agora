package com.newoether.agora.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkScanInputTest {

    // ── host parsing ────────────────────────────────────────

    @Test
    fun parsesSingleIpsCommaAndSpaceSeparated() {
        assertEquals(listOf("192.168.1.5", "10.0.0.1"), NetworkScanInput.parseHosts("192.168.1.5, 10.0.0.1"))
        assertEquals(listOf("192.168.1.5", "10.0.0.1"), NetworkScanInput.parseHosts("192.168.1.5 10.0.0.1"))
    }

    @Test
    fun expandsLastOctetDashRange() {
        val hosts = NetworkScanInput.parseHosts("192.168.1.10-12")
        assertEquals(listOf("192.168.1.10", "192.168.1.11", "192.168.1.12"), hosts)
    }

    @Test
    fun expandsSlash24Cidr() {
        val hosts = NetworkScanInput.parseHosts("192.168.1.0/24")
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun skipsInvalidTokensAndDeduplicates() {
        val hosts = NetworkScanInput.parseHosts("192.168.1.5, not-an-ip, 999.1.1.1, 192.168.1.5")
        assertEquals(listOf("192.168.1.5"), hosts)
    }

    @Test
    fun capsExpansionAtMaxHosts() {
        // Two full /24s would be 508; must cap at MAX_HOSTS.
        val hosts = NetworkScanInput.parseHosts("10.0.0.0/24, 10.0.1.0/24, 10.0.2.0/24, 10.0.3.0/24, 10.0.4.0/24")
        assertTrue(hosts.size <= NetworkScanInput.MAX_HOSTS)
    }

    // ── port parsing ────────────────────────────────────────

    @Test
    fun blankPortSpecMeansAllPorts() {
        assertEquals(65535, NetworkScanInput.parsePorts("").size)
        assertEquals(65535, NetworkScanInput.parsePorts("   ").size)
    }

    @Test
    fun parsesSinglePortsAndRanges() {
        assertEquals(listOf(22, 80, 443), NetworkScanInput.parsePorts("22,80,443"))
        assertEquals((1..10).toList(), NetworkScanInput.parsePorts("1-10"))
    }

    @Test
    fun clampsAndSkipsOutOfRangePorts() {
        // 0 and 70000 are invalid; a reversed range is normalized.
        assertEquals(listOf(80), NetworkScanInput.parsePorts("0,80,70000"))
        assertEquals((5..7).toList(), NetworkScanInput.parsePorts("7-5"))
    }

    @Test
    fun isIpv4RejectsBadInput() {
        assertTrue(NetworkScanInput.isIpv4("192.168.0.1"))
        assertFalse(NetworkScanInput.isIpv4("192.168.0"))
        assertFalse(NetworkScanInput.isIpv4("256.0.0.1"))
        assertFalse(NetworkScanInput.isIpv4("a.b.c.d"))
    }

    // ── Wake-on-LAN magic packet ────────────────────────────

    @Test
    fun magicPacketHasSyncStreamThenSixteenMacRepeats() {
        val packet = NetworkScanInput.magicPacket("01:02:03:04:05:06")!!
        assertEquals(102, packet.size) // 6 + 16*6
        // First 6 bytes are 0xFF.
        for (i in 0..5) assertEquals(0xFF.toByte(), packet[i])
        // Then the MAC repeated 16 times.
        val mac = byteArrayOf(1, 2, 3, 4, 5, 6)
        for (rep in 0 until 16) {
            for (j in 0..5) assertEquals(mac[j], packet[6 + rep * 6 + j])
        }
    }

    @Test
    fun magicPacketAcceptsDashSeparatedMac() {
        assertEquals(102, NetworkScanInput.magicPacket("AA-BB-CC-DD-EE-FF")!!.size)
    }

    @Test
    fun magicPacketRejectsMalformedMac() {
        assertNull(NetworkScanInput.magicPacket("01:02:03"))
        assertNull(NetworkScanInput.magicPacket("zz:zz:zz:zz:zz:zz"))
        assertNull(NetworkScanInput.magicPacket(""))
    }
}
