# Network Scanner

keywords: network scanner, LAN scan, host discovery, ping sweep, ARP, MAC address, OUI vendor, port scan, TCP connect, UDP probe, banner grab, gateway, reverse DNS, forward DNS, wake-on-LAN, WoL, magic packet, custom scan, CIDR, WAN IP, services

The network scanner maps the local network your device is attached to: it discovers live hosts, resolves their MAC address and hardware vendor, scans TCP and UDP ports with service and banner detection, flags the gateway, and reports the device's own LAN and WAN addresses. Everything runs on-device over raw sockets and the kernel ARP table — no external scan service is used.

Open it from **Settings → Network → Network Scanner**.

!!! warning "Scan only networks you are allowed to scan"
    The scanner sends real probes from your device and is visible to the hosts it contacts. Only use it on networks you own or are authorized to test.

## Discovering hosts

Tap **Scan Network**. The scanner:

1. Detects your device's local (LAN) addresses and the Wi-Fi gateway.
2. Sweeps each `/24` subnet, testing every address for reachability (ICMP ping, falling back to a quick TCP connect on common ports).
3. Reads the kernel ARP/neighbor table to pair discovered IPs with MAC addresses.
4. Builds a sorted host list, resolving each MAC to a hardware vendor from a bundled OUI database.

Progress and the current phase are shown live, with a **Cancel** button. The gateway is highlighted. On a cellular-only connection there is no LAN to sweep, so discovery is skipped. The top of the screen shows your **LAN** and **WAN** addresses; the WAN address is looked up once when the screen opens and on each rescan (this is the only part of the scanner that contacts the internet).

## Inspecting a host

Each host card offers:

- **Scan ports** — a TCP connect scan over a curated set of common ports. Open ports are labelled with their service (e.g. `22 ssh`, `443 https`, `3306 mysql`) and, where possible, a grabbed banner (SSH identity, HTTP `Server`/title).
- **Probe UDP** — sends crafted payloads to a fixed set of common UDP services (DNS, DHCP, NTP, SNMP, SSDP, mDNS, SIP, WireGuard, and more — 32 probes) and reports the ones that answer.
- **Wake** — sends a Wake-on-LAN magic packet to the host's MAC (enabled only when a MAC is known), to power on a sleeping machine that supports WoL.
- **Copy IP** — copies the host's address to the clipboard.

## Custom scan, DNS, and rescan (toolbar)

The top bar adds three actions:

- **Custom scan** — scan an explicit host/port spec. Hosts accept single IPs, a last-octet range (`192.168.1.10-20`), or a `/24` CIDR (`192.168.1.0/24`); ports accept single values, ranges (`1-1024`), or blank for all 65,535. The host list is capped at 1024 for safety.
- **DNS lookup** — a forward lookup (hostname → addresses) or, when you enter an IP, a reverse (PTR) lookup. Hostnames are resolved only here, on demand — the sweep itself does not fill them in.
- **Rescan** — re-runs discovery and refreshes the WAN IP.

## Limitations

- **MAC and vendor depend on an OS-readable ARP table.** On newer Android the kernel ARP/neighbor table is often restricted, in which case MAC and vendor are blank for most hosts. This is an OS restriction, not a scan failure.
- **Gateway detection is Wi-Fi only** and uses the platform DHCP API; off Wi-Fi no host is flagged as the gateway.
- **TCP scanning is connect-based** (a full handshake), not a stealth scan — it is visible to the target and its logs.
- **UDP results are reply-only.** A silent UDP port is ambiguous (filtered vs. no service), so only ports that actually answer are reported.
- Only **open** ports/services are recorded; closed and filtered ports are not listed.

See also [File Server](fileserver.md) and [Privacy & Security](privacy.md).
