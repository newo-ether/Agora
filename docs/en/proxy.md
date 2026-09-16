# Network Proxy

Open **Settings → Network Proxy** to route Agora's shared HTTP-client traffic through a configured proxy.

## Scope

The proxy can affect provider requests, model synchronization, web search, MCP over HTTP, update checks, and explicitly submitted rating/crash reports when those paths use the shared client. It does not transparently proxy:

- direct SSH connections
- local llama.cpp inference
- processes and networking inside the Alpine sandbox

The destination may still see traffic metadata, and the proxy operator can observe traffic according to the transport's encryption.

## Proxy types

- **HTTP** — a plaintext HTTP proxy. Agora opens a `CONNECT` tunnel for `https://` destinations and sends absolute-form requests for `http://` ones. The proxy address and any proxy credentials are visible on the local network path.
- **HTTPS** — the same protocol, but Agora first establishes TLS with the proxy itself (for example Caddy `forward_proxy`, or Squid on an `https_port`). The `CONNECT` request, the destination hostnames and the proxy password are all encrypted between the device and the proxy. The proxy certificate must be valid for the configured host and trusted by Android's system trust store.
- **SOCKS5** — a SOCKS5 proxy, with optional username/password authentication.

## Authentication and bypass

Configure the proxy type, host, port, optional username/password, and bypass rules accepted by the UI. Test the route before relying on it. A proxy password is included in an Agora export only if the secrets/API-key category is explicitly selected.

See [Privacy & Security](privacy.md).
