package com.newoether.agora.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HttpClient.isLocalHost] decides whether the cleartext-credential guard applies, so a
 * regression here silently either blocks a legitimate self-hosted endpoint or leaks API keys
 * onto the open internet. The Tailscale cases exist because a tailnet is an encrypted WireGuard
 * overlay — http:// inside it is not cleartext on any wire — and recognizing it is what makes
 * disabling the guard wholesale unnecessary.
 */
class HttpClientLocalHostTest {

    @Test
    fun loopbackAndPrivateRanges_areLocal() {
        listOf(
            "localhost", "::1", "127.0.0.1", "10.0.0.5", "192.168.1.10",
            "172.16.0.1", "172.31.255.254", "169.254.1.1",
        ).forEach { assertTrue(it, HttpClient.isLocalHost(it)) }
    }

    @Test
    fun lanNames_areLocal() {
        listOf("ollama", "nas.local", "box.lan", "pi.home", "svc.internal")
            .forEach { assertTrue(it, HttpClient.isLocalHost(it)) }
    }

    @Test
    fun tailscaleAddresses_areLocal() {
        listOf(
            "100.64.0.1",            // low edge of the 100.64.0.0/10 CGNAT range
            "100.101.102.103",
            "100.127.255.254",       // high edge
            "server.tailnet-abc.ts.net",
            "fd7a:115c:a1e0::1234",  // Tailscale IPv6 ULA
        ).forEach { assertTrue(it, HttpClient.isLocalHost(it)) }
    }

    @Test
    fun publicHosts_areNotLocal() {
        listOf(
            "api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com",
            "newoether.com", "8.8.8.8",
            "100.63.255.255",        // just below the CGNAT range
            "100.128.0.1",           // just above it
            "172.15.0.1", "172.32.0.1", // just outside 172.16/12
            "evil-ts.net.example.com",  // must not match the .ts.net suffix rule
        ).forEach { assertFalse(it, HttpClient.isLocalHost(it)) }
    }
}
