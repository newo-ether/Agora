package com.newoether.agora.scanner

/** A single host discovered on the local network. */
data class HostInfo(
    val ip: String,
    val mac: String? = null,
    val hostname: String? = null,
    val vendor: String? = null,
    val openPorts: List<PortResult> = emptyList(),
    val isGateway: Boolean = false,
)

/** One open port (TCP or UDP) with optional service name and grabbed banner. */
data class PortResult(
    val port: Int,
    val protocol: String = "tcp",
    val state: String = "open",
    val service: String? = null,
    val banner: String? = null,
)

/** Observable scan progress and results, projected to the UI as an immutable snapshot. */
data class ScanState(
    val isScanning: Boolean = false,
    val phase: String = "",
    val hosts: List<HostInfo> = emptyList(),
    val progress: Float = 0f,
    val wanIp: String? = null,
    val localIp: String? = null,
    val localIps: List<String> = emptyList(),
    val error: String? = null,
)
