package com.newoether.agora.scanner

/**
 * Pure, socket-free helpers for the scanner's custom-scan input and Wake-on-LAN.
 *
 * Kept separate from the engine so IP/CIDR/port parsing and magic-packet construction are unit
 * testable without touching the network. All functions are deterministic and side-effect free.
 */
internal object NetworkScanInput {

    /** Hard cap so a wide range/CIDR can't expand into a runaway host list. */
    const val MAX_HOSTS = 1024

    /**
     * Parses a custom host specification into concrete IPv4 strings. Accepts, comma- or
     * whitespace-separated:
     *  - a single IP: `192.168.1.5`
     *  - a last-octet dash range: `192.168.1.10-20`
     *  - a `/24` CIDR: `192.168.1.0/24` (expands to .1-.254)
     * Invalid tokens are skipped. The result is de-duplicated, ordered, and capped at [MAX_HOSTS].
     */
    fun parseHosts(spec: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in spec.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                token.contains('/') -> expandCidr24(token, out)
                token.contains('-') && token.substringAfterLast('.').contains('-') ->
                    expandDashRange(token, out)
                isIpv4(token) -> out.add(token)
            }
            if (out.size >= MAX_HOSTS) break
        }
        return out.take(MAX_HOSTS)
    }

    /**
     * Parses a port specification into concrete port numbers. Accepts comma/space-separated single
     * ports and `start-end` ranges. A blank spec means "all ports" (1..65535). Out-of-range values
     * are clamped/skipped.
     */
    fun parsePorts(spec: String): List<Int> {
        if (spec.isBlank()) return (1..65535).toList()
        val out = LinkedHashSet<Int>()
        for (token in spec.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (token.contains('-')) {
                val a = token.substringBefore('-').toIntOrNull()
                val b = token.substringAfter('-').toIntOrNull()
                if (a != null && b != null) {
                    val lo = minOf(a, b).coerceIn(1, 65535)
                    val hi = maxOf(a, b).coerceIn(1, 65535)
                    for (p in lo..hi) out.add(p)
                }
            } else {
                token.toIntOrNull()?.takeIf { it in 1..65535 }?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    private fun expandDashRange(token: String, out: MutableSet<String>) {
        val prefix = token.substringBeforeLast('.')
        val lastPart = token.substringAfterLast('.')
        val start = lastPart.substringBefore('-').toIntOrNull() ?: return
        val end = lastPart.substringAfter('-').toIntOrNull() ?: return
        if (!isIpv4("$prefix.$start") || start !in 0..255 || end !in 0..255) return
        for (i in minOf(start, end)..maxOf(start, end)) {
            out.add("$prefix.$i")
            if (out.size >= MAX_HOSTS) return
        }
    }

    private fun expandCidr24(token: String, out: MutableSet<String>) {
        val ip = token.substringBefore('/')
        val bits = token.substringAfter('/').toIntOrNull() ?: return
        if (bits != 24 || !isIpv4(ip)) return
        val base = ip.substringBeforeLast('.')
        for (i in 1..254) {
            out.add("$base.$i")
            if (out.size >= MAX_HOSTS) return
        }
    }

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /**
     * Builds a standard Wake-on-LAN "magic packet": 6 bytes of 0xFF followed by the 6-byte MAC
     * repeated 16 times (102 bytes total). Returns null for a malformed MAC. Accepts `:` or `-`
     * separated hex.
     */
    fun magicPacket(mac: String): ByteArray? {
        val macBytes = mac.split(':', '-').mapNotNull {
            it.trim().toIntOrNull(16)?.takeIf { v -> v in 0..255 }?.toByte()
        }
        if (macBytes.size != 6) return null
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0..5) packet[i] = 0xFF.toByte()
        for (i in 0..15) System.arraycopy(macBytes.toByteArray(), 0, packet, 6 + i * 6, 6)
        return packet
    }
}
