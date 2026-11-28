package com.newoether.agora.scanner

import android.content.Context
import android.net.wifi.WifiManager
import com.newoether.agora.util.DebugLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Low-level, side-effecting network helpers for the scanner: ARP/neighbor reads, TCP banner
 * grabbing, UDP probing and response parsing, local-interface enumeration, and OUI vendor lookup.
 * Separated from [NetworkScanner] so the engine file stays within the per-file line cap.
 */
internal class NetworkScanNet(private val context: Context) {

    private val ouiDb: Map<String, String> by lazy { loadOuiDb() }

    fun lookupVendor(mac: String?): String? {
        if (mac == null) return null
        return ouiDb[mac.replace(":", "").take(6).uppercase()]
    }

    private fun loadOuiDb(): Map<String, String> {
        val db = mutableMapOf<String, String>()
        runCatching {
            context.assets.open("oui.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.length > 7 && !line.startsWith("#")) {
                        val sep = line.indexOf('\t')
                        if (sep > 0) db[line.substring(0, sep).uppercase()] = line.substring(sep + 1)
                    }
                }
            }
        }
        return db
    }

    /** Reads the kernel ARP/neighbor table to map discovered IPs to MACs. */
    fun readNeighborTable(): List<Pair<String, String>> {
        parseArp()?.takeIf { it.isNotEmpty() }?.let { return it }
        return parseIpNeigh()
    }

    private fun parseArp(): List<Pair<String, String>>? {
        val results = mutableListOf<Pair<String, String>>()
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/arp"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lines().drop(1).forEach { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip = parts[0]
                    val flags = parts[2]
                    val mac = parts[3].uppercase()
                    if (flags != "0x0" && mac != "00:00:00:00:00:00" && mac.contains(":")) {
                        results.add(ip to mac)
                    }
                }
            }
            results
        }.onFailure { DebugLog.w(TAG, "arp read failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun parseIpNeigh(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/ip", "neigh"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lines().forEach { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 5) {
                    val ip = parts[0]
                    val stateStr = parts.last()
                    if (stateStr != "FAILED" && stateStr != "INCOMPLETE") {
                        val llIdx = parts.indexOf("lladdr")
                        val mac = if (llIdx >= 0 && llIdx + 1 < parts.size) {
                            parts[llIdx + 1].uppercase()
                        } else {
                            null
                        }
                        if (mac != null && mac != "00:00:00:00:00:00" && mac.contains(":")) {
                            results.add(ip to mac)
                        }
                    }
                }
            }
        }.onFailure { DebugLog.w(TAG, "ip neigh failed: ${it.javaClass.simpleName}") }
        return results
    }

    fun grabBanner(socket: Socket, ip: String, port: Int): String? = try {
        socket.soTimeout = 1500
        when (port) {
            22 -> socket.getInputStream().bufferedReader().readLine()?.take(100)
            80, 443, 8080, 8443 -> grabHttpBanner(socket, ip)
            else -> grabGenericBanner(socket)
        }
    } catch (_: Exception) {
        null
    }

    private fun grabHttpBanner(socket: Socket, ip: String): String? {
        val out = socket.getOutputStream()
        out.write("GET / HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        val resp = socket.getInputStream().bufferedReader().readText().take(512)
        val server = Regex("Server:\\s*(.+)", RegexOption.IGNORE_CASE)
            .find(resp)?.groupValues?.get(1)?.trim()
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
            .find(resp)?.groupValues?.get(1)?.trim()
        return listOfNotNull(server, title).joinToString(" | ").ifBlank { null }
    }

    private fun grabGenericBanner(socket: Socket): String? {
        socket.soTimeout = 500
        val byte = socket.getInputStream().read()
        if (byte < 0) return null
        val buf = ByteArray(99)
        buf[0] = byte.toByte()
        val n = socket.getInputStream().read(buf, 1, 98)
        return String(buf, 0, if (n > 0) n + 1 else 1).trim().take(100).ifBlank { null }
    }

    fun probeUdp(ip: String, port: Int, payload: ByteArray): ByteArray? = try {
        DatagramSocket().use { sock ->
            sock.soTimeout = 2000
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), port))
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            buf.copyOf(resp.length)
        }
    } catch (_: Exception) {
        null
    }

    /** Sends a Wake-on-LAN magic packet for [mac] as a UDP broadcast to port 9. */
    suspend fun sendWakeOnLan(mac: String, broadcastIp: String): Boolean =
        withContext(Dispatchers.IO) {
            val packet = NetworkScanInput.magicPacket(mac) ?: return@withContext false
            runCatching {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcastIp), 9))
                }
                true
            }.getOrDefault(false)
        }

    /** Best-effort public WAN IP via two public echo endpoints; the only off-LAN call. */
    suspend fun fetchWanIp(): String = withContext(Dispatchers.IO) {
        fun get(url: String): String? = runCatching {
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                if (responseCode == 200) {
                    inputStream.bufferedReader().use { it.readText() }.trim().also { disconnect() }
                } else {
                    "HTTP $responseCode".also { disconnect() }
                }
            }
        }.getOrNull()
        get("https://api.ipify.org") ?: get("https://checkip.amazonaws.com") ?: "Unavailable"
    }

    fun getAllLocalIps(): List<String> {
        val ips = mutableListOf<String>()
        runCatching {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback || isCellularInterface(intf.name)) continue
                for (ifAddr in intf.interfaceAddresses) {
                    val addr = ifAddr.address ?: continue
                    if (addr.isLoopbackAddress || addr is Inet6Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (isPrivateIp(ip)) ips.add(ip)
                }
            }
        }
        return ips.distinct()
    }

    fun getScannableSubnets(localIps: List<String>): List<String> {
        val subnets = mutableSetOf<String>()
        runCatching {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback || isCellularInterface(intf.name)) continue
                for (ifAddr in intf.interfaceAddresses) {
                    val addr = ifAddr.address ?: continue
                    if (addr.isLoopbackAddress || addr is Inet6Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (!isPrivateIp(ip)) continue
                    if (ifAddr.networkPrefixLength.toInt() in 8..32) {
                        subnets.add(ip.substringBeforeLast("."))
                    }
                }
            }
        }
        localIps.forEach { subnets.add(it.substringBeforeLast(".")) }
        return subnets.toList()
    }

    @Suppress("DEPRECATION")
    fun getGatewayIp(): String? = runCatching {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val gw = wm.dhcpInfo?.gateway ?: 0
        if (gw == 0) {
            null
        } else {
            "${gw and 0xff}.${gw shr 8 and 0xff}.${gw shr 16 and 0xff}.${gw shr 24 and 0xff}"
        }
    }.getOrNull()

    fun isCellularIp(ip: String): Boolean {
        runCatching {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || !isCellularInterface(intf.name)) continue
                for (ifAddr in intf.interfaceAddresses) {
                    if (ifAddr.address?.hostAddress == ip) return true
                }
            }
        }
        return ip.startsWith("100.") &&
            ip.split(".").getOrNull(1)?.toIntOrNull()?.let { it in 64..127 } == true
    }

    companion object {
        private const val TAG = "NetworkScanNet"

        fun isCellularInterface(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")
        }

        fun isPrivateIp(ip: String): Boolean =
            ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")

        fun ipToLong(ip: String): Long =
            ip.split(".").fold(0L) { acc, v -> acc * 256 + (v.toIntOrNull() ?: 0) }

        fun parseUdpResponse(port: Int, data: ByteArray): String? = when (port) {
            137 -> parseNetBiosName(data)
            161 -> parseSnmpResponse(data)
            1900 -> String(data).lines().firstOrNull { it.startsWith("SERVER:", true) }
                ?.substringAfter(":")?.trim()
            5060 -> String(data).lines().firstOrNull()?.take(60)
            53, 123, 500, 5353 -> "responded"
            else -> if (data.isNotEmpty()) "responded (${data.size}B)" else null
        }

        private fun parseNetBiosName(data: ByteArray): String? {
            if (data.size < 57) return null
            return runCatching {
                val nameCount = data[56].toInt() and 0xFF
                if (nameCount > 0 && data.size > 57 + 18) {
                    String(data, 57, 15).trim { it <= ' ' || it == '\u0000' }
                } else {
                    null
                }
            }.getOrNull()
        }

        private fun parseSnmpResponse(data: ByteArray): String? = runCatching {
            val printable = String(data, Charsets.US_ASCII).filter { it in ' '..'~' }
            if (printable.length > 5) printable.take(80) else null
        }.getOrNull()
    }
}
