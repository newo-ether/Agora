package com.newoether.agora.scanner

import java.io.ByteArrayOutputStream

/**
 * Static scan tables: the default TCP port set, service-name map, and the crafted UDP probe
 * payloads used to elicit a response from common non-TCP services. Kept apart from the engine so
 * each file stays well under the repository's per-file line cap.
 */
internal object NetworkScanTables {

    val DEFAULT_PORTS = listOf(
        21, 22, 23, 25, 53, 67, 68, 69, 80, 110, 111, 119, 123, 135, 137, 138, 139, 143,
        161, 162, 179, 389, 443, 445, 465, 500, 514, 515, 548, 554, 587, 631, 636,
        993, 995, 1025, 1080, 1194,
        1000, 1111, 1433, 1521, 1723, 1883,
        2000, 2049, 2082, 2083, 2222, 2375, 2376,
        3000, 3128, 3268, 3306, 3333, 3389, 3478,
        4000, 4080, 4443, 4500, 4567, 4662, 4848,
        5000, 5001, 5004, 5060, 5222, 5353, 5432, 5555, 5601, 5631, 5632, 5800, 5900, 5901,
        6443, 6379, 6667, 6881,
        7070, 7443, 7547, 7777, 8000, 8008, 8080, 8081, 8088, 8123, 8200, 8291, 8443, 8444,
        8500, 8545, 8686, 8834, 8888, 8889, 8983,
        9000, 9001, 9090, 9091, 9100, 9200, 9300, 9418, 9443, 9993,
        10000, 10001, 11211, 15672, 17500, 19132, 25565, 27017, 28015, 32400, 32768,
        49152, 51413, 51820, 55555,
    )

    val FULL_PORTS = (1..65535).toList()

    val SERVICES = mapOf(
        21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns",
        67 to "dhcp-s", 68 to "dhcp-c", 69 to "tftp", 80 to "http", 110 to "pop3",
        111 to "rpc", 119 to "nntp", 123 to "ntp", 135 to "msrpc", 137 to "netbios",
        138 to "netbios", 139 to "netbios", 143 to "imap", 161 to "snmp", 162 to "snmptrap",
        179 to "bgp", 389 to "ldap", 443 to "https", 445 to "smb", 465 to "smtps",
        500 to "ike", 514 to "syslog", 515 to "lpd", 548 to "afp", 554 to "rtsp",
        587 to "submission", 631 to "ipp", 636 to "ldaps",
        993 to "imaps", 995 to "pop3s", 1025 to "msrpc", 1080 to "socks",
        1194 to "openvpn", 1000 to "custom", 1111 to "custom",
        1433 to "mssql", 1521 to "oracle", 1723 to "pptp", 1883 to "mqtt",
        2000 to "custom", 2049 to "nfs", 2082 to "cpanel", 2083 to "cpanel-s",
        2222 to "ssh-alt", 2375 to "docker", 2376 to "docker-s",
        3000 to "dev-http", 3128 to "squid", 3268 to "ldap-gc", 3306 to "mysql",
        3333 to "custom", 3389 to "rdp", 3478 to "stun",
        4000 to "custom", 4080 to "http-alt", 4443 to "https-alt",
        4500 to "ike-nat", 4567 to "custom", 4662 to "edonkey", 4848 to "appserver",
        5000 to "upnp", 5001 to "synology", 5004 to "rtp", 5060 to "sip",
        5222 to "xmpp", 5353 to "mdns", 5432 to "postgres", 5555 to "adb",
        5601 to "kibana", 5631 to "pcanywhere", 5632 to "pcanywhere",
        5800 to "vnc-http", 5900 to "vnc", 5901 to "vnc",
        6443 to "k8s-api", 6379 to "redis", 6667 to "irc", 6881 to "bittorrent",
        7070 to "realserver", 7443 to "https-alt", 7547 to "cwmp", 7777 to "custom",
        8000 to "http-alt", 8008 to "http-alt", 8080 to "http-proxy",
        8081 to "http-alt", 8088 to "http-alt", 8123 to "home-asst",
        8200 to "trivnet", 8291 to "mikrotik", 8443 to "https-alt", 8444 to "https-alt",
        8500 to "consul", 8545 to "ethereum", 8686 to "sun-mgmt",
        8834 to "nessus", 8888 to "http-alt", 8889 to "http-alt", 8983 to "solr",
        9000 to "custom", 9001 to "custom", 9090 to "prometheus",
        9091 to "transmission", 9100 to "jetdirect", 9200 to "elastic",
        9300 to "elastic", 9418 to "git", 9443 to "https-alt", 9993 to "zerotier",
        10000 to "webmin", 10001 to "custom", 11211 to "memcached",
        15672 to "rabbitmq", 17500 to "dropbox", 19132 to "minecraft-be",
        25565 to "minecraft", 27017 to "mongodb", 28015 to "rethinkdb",
        32400 to "plex", 32768 to "custom", 49152 to "upnp",
        51413 to "bittorrent", 51820 to "wireguard", 55555 to "custom",
    )

    val COMMON_TCP_PORTS = listOf(21, 22, 23, 25, 53, 80, 443, 445, 8080, 8443, 3389, 5900)

    /** Cheap liveness-probe ports used during host discovery when ICMP is unavailable. */
    val LIVENESS_PORTS = intArrayOf(7, 80, 443, 22, 445, 139, 8080, 3389)

    private val NBSTAT_QUERY = byteArrayOf(
        0x80.toByte(), 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x20, 0x43, 0x4B, 0x41,
        0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
        0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
        0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
        0x41, 0x41, 0x41, 0x41, 0x41, 0x00, 0x00, 0x21,
        0x00, 0x01,
    )

    private val SNMP_GET_REQUEST = byteArrayOf(
        0x30, 0x29, 0x02, 0x01, 0x00, 0x04, 0x06, 0x70, 0x75, 0x62, 0x6C, 0x69, 0x63,
        0xA0.toByte(), 0x1C, 0x02, 0x04, 0x00, 0x00, 0x00, 0x01, 0x02, 0x01, 0x00,
        0x02, 0x01, 0x00,
        0x30, 0x0E, 0x30, 0x0C, 0x06, 0x08, 0x2B, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00,
        0x05, 0x00,
    )

    private val DHCP_DISCOVER = byteArrayOf(
        0x01, 0x01, 0x06, 0x00, 0x12, 0x34, 0x56, 0x78.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    private val SSDP_MSEARCH = (
        "M-SEARCH * HTTP/1.1\r\nHost:239.255.255.250:1900\r\n" +
            "ST:ssdp:all\r\nMAN:\"ssdp:discover\"\r\nMX:2\r\n\r\n"
        ).toByteArray()

    private val IKE_INIT = byteArrayOf(
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x20, 0x22, 0x08, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x1C, 0x00, 0x00, 0x00, 0x00,
    )

    private const val SIP_OPTIONS =
        "OPTIONS sip:nm SIP/2.0\r\nVia: SIP/2.0/UDP nm;branch=z9hG4bK0\r\n" +
            "From: <sip:nm@nm>;tag=0\r\nTo: <sip:nm@nm>\r\n" +
            "Call-ID: 0@0.0.0.0\r\nCSeq: 0 OPTIONS\r\n" +
            "Max-Forwards: 0\r\nContent-Length: 0\r\n\r\n"

    val UDP_PROBES: List<Triple<Int, String, ByteArray>> = listOf(
        Triple(53, "dns", buildStaticDnsQuery()),
        Triple(67, "dhcp", DHCP_DISCOVER),
        Triple(
            69,
            "tftp",
            byteArrayOf(0x00, 0x01, 0x2F, 0x00, 0x6E, 0x65, 0x74, 0x61, 0x73, 0x63, 0x69, 0x69, 0x00),
        ),
        Triple(123, "ntp", ByteArray(48).also { it[0] = 0x1B }),
        Triple(137, "netbios", NBSTAT_QUERY),
        Triple(138, "netbios-dgm", ByteArray(10)),
        Triple(161, "snmp", SNMP_GET_REQUEST),
        Triple(162, "snmptrap", SNMP_GET_REQUEST),
        Triple(
            389,
            "ldap",
            byteArrayOf(
                0x30, 0x0C, 0x02, 0x01, 0x01, 0x60, 0x07, 0x02, 0x01, 0x03, 0x04, 0x00,
                0x80.toByte(), 0x00,
            ),
        ),
        Triple(500, "ike", IKE_INIT),
        Triple(514, "syslog", "<14>test".toByteArray()),
        Triple(520, "rip", byteArrayOf(0x01, 0x01, 0x00, 0x00) + ByteArray(20)),
        Triple(
            623,
            "ipmi",
            byteArrayOf(
                0x06, 0x00, 0xFF.toByte(), 0x07, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x09, 0x20, 0x18.toByte(),
                0xC8.toByte(), 0x81.toByte(), 0x00, 0x38, 0x8E.toByte(), 0x04, 0xB5.toByte(),
            ),
        ),
        Triple(1194, "openvpn", byteArrayOf(0x38, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
        Triple(1604, "citrix", byteArrayOf(0x1E, 0x00, 0x01, 0x30, 0x02, 0xFD.toByte()) + ByteArray(26)),
        Triple(1900, "ssdp", SSDP_MSEARCH),
        Triple(
            2049,
            "nfs",
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
                0x00, 0x01, 0x86.toByte(), 0xA3.toByte(), 0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x00,
            ),
        ),
        Triple(
            3478,
            "stun",
            byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42) + ByteArray(12),
        ),
        Triple(3702, "ws-discovery", "<?xml version=\"1.0\"?><Probe/>".toByteArray()),
        Triple(4500, "ike-nat", IKE_INIT),
        Triple(5060, "sip", SIP_OPTIONS.toByteArray()),
        Triple(5353, "mdns", buildStaticMdnsQuery()),
        Triple(5632, "pcanywhere", byteArrayOf(0x4E, 0x51)),
        Triple(
            6481,
            "sun-rpc",
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
                0x00, 0x01, 0x86.toByte(), 0xA0.toByte(), 0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x04,
            ),
        ),
        Triple(10001, "ubiquiti", byteArrayOf(0x01, 0x00, 0x00, 0x00)),
        Triple(11211, "memcached", "stats\r\n".toByteArray()),
        Triple(17185, "vxworks", byteArrayOf(0x00, 0x00, 0x55, 0x55)),
        Triple(
            27960,
            "quake",
            byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0x67, 0x65, 0x74, 0x73, 0x74, 0x61, 0x74, 0x75, 0x73,
            ),
        ),
        Triple(32414, "plex", "HELLO\n".toByteArray()),
        Triple(44818, "ethernetip", byteArrayOf(0x63, 0x00, 0x00, 0x00) + ByteArray(20)),
        Triple(
            47808,
            "bacnet",
            byteArrayOf(
                0x81.toByte(), 0x0A, 0x00, 0x11, 0x01, 0x04, 0x00, 0x05,
                0x01, 0x0C, 0x0C, 0x02, 0x3F, 0xFF.toByte(), 0xFF.toByte(), 0x19, 0x4B,
            ),
        ),
        Triple(51820, "wireguard", ByteArray(32).also { it[0] = 0x01 }),
    )

    private fun buildStaticDnsQuery(): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (part in "version.bind".split(".")) {
            buf.write(part.length)
            buf.write(part.toByteArray())
        }
        buf.write(byteArrayOf(0x00, 0x00, 0x10, 0x00, 0x03))
        return buf.toByteArray()
    }

    private fun buildStaticMdnsQuery(): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (part in "_services._dns-sd._udp.local".split(".")) {
            buf.write(part.length)
            buf.write(part.toByteArray())
        }
        buf.write(byteArrayOf(0x00, 0x00, 0x0C, 0x00, 0x01))
        return buf.toByteArray()
    }
}
