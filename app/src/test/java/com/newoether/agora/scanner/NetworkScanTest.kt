package com.newoether.agora.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkScanNetTest {

    @Test
    fun ipToLongOrdersAddressesNumerically() {
        assertTrue(NetworkScanNet.ipToLong("192.168.1.2") < NetworkScanNet.ipToLong("192.168.1.10"))
        assertTrue(NetworkScanNet.ipToLong("10.0.0.1") < NetworkScanNet.ipToLong("192.168.0.1"))
        assertEquals(0L, NetworkScanNet.ipToLong("0.0.0.0"))
    }

    @Test
    fun isPrivateIpMatchesRfc1918Ranges() {
        assertTrue(NetworkScanNet.isPrivateIp("192.168.1.5"))
        assertTrue(NetworkScanNet.isPrivateIp("10.1.2.3"))
        assertTrue(NetworkScanNet.isPrivateIp("172.16.0.1"))
        assertFalse(NetworkScanNet.isPrivateIp("8.8.8.8"))
        assertFalse(NetworkScanNet.isPrivateIp("100.64.0.1"))
    }

    @Test
    fun isCellularInterfaceRecognizesRadioInterfaces() {
        assertTrue(NetworkScanNet.isCellularInterface("rmnet0"))
        assertTrue(NetworkScanNet.isCellularInterface("ccmni1"))
        assertTrue(NetworkScanNet.isCellularInterface("pdp0"))
        assertFalse(NetworkScanNet.isCellularInterface("wlan0"))
        assertFalse(NetworkScanNet.isCellularInterface("eth0"))
    }

    @Test
    fun parseUdpResponseLabelsKnownPortsAndFallsBack() {
        assertEquals("responded", NetworkScanNet.parseUdpResponse(53, byteArrayOf(1, 2, 3)))
        assertEquals("responded (3B)", NetworkScanNet.parseUdpResponse(9999, byteArrayOf(1, 2, 3)))
        assertNull(NetworkScanNet.parseUdpResponse(9999, byteArrayOf()))
    }

    @Test
    fun ssdpResponseExtractsServerHeader() {
        val payload = "HTTP/1.1 200 OK\r\nSERVER: Linux/UPnP\r\n\r\n".toByteArray()
        assertEquals("Linux/UPnP", NetworkScanNet.parseUdpResponse(1900, payload))
    }
}

class NetworkScanTablesTest {

    @Test
    fun defaultPortsAreUniqueAndInRange() {
        val ports = NetworkScanTables.DEFAULT_PORTS
        assertEquals(ports.size, ports.toSet().size)
        assertTrue(ports.all { it in 1..65535 })
    }

    @Test
    fun fullPortsCoverEntireRange() {
        assertEquals(65535, NetworkScanTables.FULL_PORTS.size)
        assertEquals(1, NetworkScanTables.FULL_PORTS.first())
        assertEquals(65535, NetworkScanTables.FULL_PORTS.last())
    }

    @Test
    fun wellKnownServicesAreMapped() {
        assertEquals("ssh", NetworkScanTables.SERVICES[22])
        assertEquals("https", NetworkScanTables.SERVICES[443])
        assertEquals("wireguard", NetworkScanTables.SERVICES[51820])
    }

    @Test
    fun udpProbesHaveNonEmptyPayloadsAndKnownPorts() {
        assertTrue(NetworkScanTables.UDP_PROBES.isNotEmpty())
        NetworkScanTables.UDP_PROBES.forEach { (port, name, payload) ->
            assertTrue("port $port in range", port in 1..65535)
            assertTrue("name not blank", name.isNotBlank())
            assertTrue("payload for $name non-empty", payload.isNotEmpty())
        }
        // The DNS version.bind query must carry the standard transaction id prefix.
        val dns = NetworkScanTables.UDP_PROBES.first { it.first == 53 }.third
        assertEquals(0x12.toByte(), dns[0])
        assertEquals(0x34.toByte(), dns[1])
    }
}
