package com.newoether.agora.scanner

import android.content.Context
import com.newoether.agora.util.DebugLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-device LAN scanner: host discovery (TCP liveness + ARP), TCP port scan with banner grab,
 * subnet sweep, and UDP service probing. Progress and results are published as [ScanState]
 * snapshots. Pure net/table work lives in [NetworkScanNet] and [NetworkScanTables]; this class
 * owns orchestration, concurrency, and the observable state.
 */
class NetworkScanner(context: Context) {

    private val net = NetworkScanNet(context)
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null

    fun launchDiscoverHosts() {
        scanJob?.cancel()
        scanJob = scope.launch { discoverHosts() }
    }

    fun launchScanPorts(ip: String, ports: List<Int> = NetworkScanTables.DEFAULT_PORTS, timeout: Int = 500) {
        scanJob?.cancel()
        scanJob = scope.launch { scanPorts(ip, ports, timeout) }
    }

    fun launchUdpProbe(ip: String) {
        scanJob?.cancel()
        scanJob = scope.launch { udpProbe(ip) }
    }

    /** Port-scans an explicit list of hosts (from the custom-scan sheet). */
    fun launchCustomScan(ips: List<String>, ports: List<Int>, timeout: Int = 500) {
        scanJob?.cancel()
        scanJob = scope.launch {
            _state.value = _state.value.copy(isScanning = true, phase = "Custom scan...", progress = 0f)
            ips.forEachIndexed { idx, ip ->
                scanPorts(ip, ports, timeout)
                _state.value = _state.value.copy(progress = (idx + 1).toFloat() / ips.size)
            }
        }
    }

    /** Sweeps an arbitrary /24 the user types (e.g. the far side of a VPN). */
    fun launchSubnetScan(subnet: String) {
        scanJob?.cancel()
        scanJob = scope.launch { scanSubnet(subnet) }
    }

    /** Fetches the public WAN IP; the only part of the scanner that leaves the LAN. */
    fun launchFetchWanIp() {
        scope.launch { fetchWanIp() }
    }

    /** Sends a Wake-on-LAN magic packet to [mac]; returns false on a malformed MAC or send error. */
    suspend fun wakeOnLan(mac: String, broadcastIp: String = "255.255.255.255"): Boolean =
        net.sendWakeOnLan(mac, broadcastIp)

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(isScanning = false, phase = "Cancelled", progress = 0f)
    }

    /** Suspending entry point for a one-shot discovery used by the tool surface. */
    suspend fun discoverBlocking(): ScanState {
        discoverHosts()
        return _state.value
    }

    /** Suspending TCP scan used by the tool surface. */
    suspend fun scanPortsBlocking(ip: String, ports: List<Int>, timeout: Int = 500): ScanState {
        scanPorts(ip, ports, timeout)
        return _state.value
    }

    private suspend fun discoverHosts() {
        _state.value = ScanState(isScanning = true, phase = "Detecting networks...", wanIp = _state.value.wanIp)
        val localIps = net.getAllLocalIps()
        val gatewayIp = net.getGatewayIp()
        _state.value = _state.value.copy(localIp = localIps.firstOrNull(), localIps = localIps)

        if (localIps.isEmpty()) {
            _state.value = _state.value.copy(isScanning = false, error = "No network connection")
            return
        }
        val scannable = localIps.filter { !net.isCellularIp(it) }
        if (scannable.isEmpty()) {
            _state.value = _state.value.copy(
                isScanning = false,
                phase = "Cellular network — subnet scan skipped",
                progress = 1f,
            )
            return
        }

        val aliveHosts = ConcurrentHashMap<String, Boolean>()
        val subnets = net.getScannableSubnets(scannable)
        val totalHosts = subnets.size * 254
        val scannedCount = AtomicInteger(0)

        subnets.forEachIndexed { subIdx, subnet ->
            _state.value = _state.value.copy(
                phase = "Scanning $subnet.0/24 (${subIdx + 1}/${subnets.size})...",
            )
            sweepSubnet(subnet, aliveHosts) { done ->
                _state.value = _state.value.copy(progress = done.toFloat() / totalHosts * 0.8f)
            }
            scannedCount.addAndGet(254)
        }

        delay(600)
        _state.value = _state.value.copy(phase = "Reading ARP table...")
        val neighbors = net.readNeighborTable().toMap()
        val allIps = (aliveHosts.keys + neighbors.keys).distinct()

        _state.value = _state.value.copy(phase = "Building results...", progress = 0.9f)
        val hosts = allIps.map { ip ->
            val mac = neighbors[ip]
            HostInfo(ip = ip, mac = mac, vendor = net.lookupVendor(mac), isGateway = ip == gatewayIp)
        }.sortedBy { NetworkScanNet.ipToLong(it.ip) }

        _state.value = _state.value.copy(
            isScanning = false,
            phase = "Done — ${hosts.size} hosts (${subnets.size} subnets)",
            progress = 1f,
            hosts = hosts,
        )
    }

    private suspend fun sweepSubnet(
        subnet: String,
        aliveHosts: ConcurrentHashMap<String, Boolean>,
        onProgress: (Int) -> Unit,
    ) {
        val base = aliveHosts.size
        (1..254).chunked(50).forEachIndexed { chunkIdx, batch ->
            coroutineScope {
                batch.map { i ->
                    async(Dispatchers.IO) {
                        val ip = "$subnet.$i"
                        if (isHostAlive(ip)) aliveHosts[ip] = true
                    }
                }.awaitAll()
            }
            onProgress(base + (chunkIdx + 1) * 50)
        }
    }

    private fun isHostAlive(ip: String): Boolean {
        try {
            if (InetAddress.getByName(ip).isReachable(800)) return true
        } catch (_: Exception) {
        }
        for (port in NetworkScanTables.LIVENESS_PORTS) {
            try {
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(ip, port), 400)
                }
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private suspend fun scanPorts(ip: String, ports: List<Int>, timeout: Int) {
        _state.value = _state.value.copy(isScanning = true, phase = "TCP $ip — 0/${ports.size}", progress = 0f)
        val results = java.util.Collections.synchronizedList(mutableListOf<PortResult>())
        val scanned = AtomicInteger(0)
        val total = ports.size
        val grabBanners = total <= 1000
        val effectiveTimeout = if (grabBanners) timeout else 500

        coroutineScope {
            ports.chunked(500).forEach { chunk ->
                chunk.map { port ->
                    async(Dispatchers.IO) {
                        connectPort(ip, port, effectiveTimeout, grabBanners)?.let { results.add(it) }
                        val done = scanned.incrementAndGet()
                        if (done % 100 == 0 || done == total) {
                            _state.value = _state.value.copy(
                                phase = "TCP $ip — $done/$total",
                                progress = done.toFloat() / total,
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        val found = results.map { it.port }.toSet()
        NetworkScanTables.COMMON_TCP_PORTS.filter { it !in found }.forEach { port ->
            connectPort(ip, port, 1000, grabBanner = true)?.let { results.add(it) }
        }

        val sorted = results.sortedBy { it.port }
        val existingUdp = _state.value.hosts.firstOrNull { it.ip == ip }
            ?.openPorts?.filter { it.protocol == "udp" } ?: emptyList()
        val merged = (sorted + existingUdp).sortedBy { it.port }
        _state.value = _state.value.copy(
            isScanning = false,
            phase = "Done — ${sorted.size} TCP ports open",
            progress = 1f,
            hosts = updateHostPorts(ip, merged),
        )
    }

    private fun connectPort(ip: String, port: Int, timeout: Int, grabBanner: Boolean): PortResult? = try {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, port), timeout)
            val banner = if (grabBanner) net.grabBanner(s, ip, port) else null
            PortResult(port, "tcp", "open", NetworkScanTables.SERVICES[port], banner)
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun udpProbe(ip: String) {
        _state.value = _state.value.copy(isScanning = true, phase = "UDP probing $ip...", progress = 0f)
        val results = java.util.Collections.synchronizedList(mutableListOf<PortResult>())
        val probes = NetworkScanTables.UDP_PROBES
        val total = probes.size

        coroutineScope {
            probes.mapIndexed { idx, (port, name, payload) ->
                async(Dispatchers.IO) {
                    net.probeUdp(ip, port, payload)?.let { resp ->
                        results.add(
                            PortResult(port, "udp", "open", name, NetworkScanNet.parseUdpResponse(port, resp)),
                        )
                    }
                    _state.value = _state.value.copy(progress = (idx + 1).toFloat() / total)
                }
            }.awaitAll()
        }

        val existingTcp = _state.value.hosts.firstOrNull { it.ip == ip }
            ?.openPorts?.filter { it.protocol == "tcp" } ?: emptyList()
        val merged = (existingTcp + results).sortedBy { it.port }
        _state.value = _state.value.copy(
            isScanning = false,
            phase = "Done — ${results.size} UDP services found",
            progress = 1f,
            hosts = updateHostPorts(ip, merged),
        )
    }

    private fun updateHostPorts(ip: String, ports: List<PortResult>): List<HostInfo> {
        val hosts = _state.value.hosts
        return if (hosts.any { it.ip == ip }) {
            hosts.map { if (it.ip == ip) it.copy(openPorts = ports) else it }
        } else {
            (hosts + HostInfo(ip = ip, openPorts = ports)).sortedBy { NetworkScanNet.ipToLong(it.ip) }
        }
    }

    suspend fun reverseDns(ip: String): String = withContext(Dispatchers.IO) {
        try {
            val hostname = InetAddress.getByName(ip).canonicalHostName
            if (hostname == ip) "No PTR record" else hostname
        } catch (e: Exception) {
            "Error: ${e.javaClass.simpleName}"
        }
    }

    /** Forward DNS resolution; returns all resolved addresses newline-joined, for the DNS sheet. */
    suspend fun dnsLookup(host: String): String = withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.joinToString("\n")
                .ifBlank { "No addresses" }
        } catch (e: Exception) {
            "Error: ${e.javaClass.simpleName}"
        }
    }

    /** The same ICMP+TCP /24 sweep as discovery, for an arbitrary subnet the user types. */
    private suspend fun scanSubnet(subnet: String) {
        _state.value = _state.value.copy(isScanning = true, phase = "Scanning $subnet.0/24...", progress = 0f)
        val aliveHosts = ConcurrentHashMap<String, Boolean>()
        sweepSubnet(subnet, aliveHosts) { done ->
            _state.value = _state.value.copy(progress = done.toFloat() / 254f * 0.8f)
        }
        delay(500)
        _state.value = _state.value.copy(phase = "Reading ARP table...")
        val neighbors = net.readNeighborTable().toMap()
        val allIps = (aliveHosts.keys + neighbors.keys.filter { it.startsWith("$subnet.") }).distinct()
        val gatewayIp = net.getGatewayIp()
        val newHosts = allIps.map { ip ->
            val mac = neighbors[ip]
            HostInfo(ip = ip, mac = mac, vendor = net.lookupVendor(mac), isGateway = ip == gatewayIp)
        }
        val existingOther = _state.value.hosts.filter { !it.ip.startsWith("$subnet.") }
        val merged = (existingOther + newHosts).sortedBy { NetworkScanNet.ipToLong(it.ip) }
        _state.value = _state.value.copy(
            isScanning = false,
            phase = "Done — ${newHosts.size} hosts on $subnet.0/24",
            progress = 1f,
            hosts = merged,
        )
    }

    private suspend fun fetchWanIp() {
        _state.value = _state.value.copy(wanIp = net.fetchWanIp())
    }

    companion object {
        private const val TAG = "NetworkScanner"

        @Volatile
        private var instance: NetworkScanner? = null

        fun getInstance(context: Context): NetworkScanner = instance ?: synchronized(this) {
            instance ?: NetworkScanner(context.applicationContext).also {
                instance = it
                DebugLog.d(TAG, "scanner initialized")
            }
        }
    }
}
