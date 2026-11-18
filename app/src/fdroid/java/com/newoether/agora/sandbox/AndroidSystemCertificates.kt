package com.newoether.agora.sandbox

import java.io.File

/**
 * Builds a PEM trust bundle from the device's own system certificate stores and installs it into a
 * rootfs.
 *
 * The base images ship no trust anchors and PRoot cannot run `update-ca-certificates`, so apt fails
 * TLS verification against the network's DNS-intercepted mirror before a single package is
 * downloaded. The device already holds a current Android trust store; copying it into the paths the
 * guest's OpenSSL and GnuTLS builds read by default is enough for repository verification without
 * weakening signature checks.
 */
internal object AndroidSystemCertificates {
    private val systemStores = listOf(
        "/system/etc/security/cacerts",
        "/apex/com.android.conscrypt/cacerts",
    )

    /** Paths Debian, Ubuntu and Alpine builds read as their default trust anchors. */
    private val guestBundles = listOf(
        "etc/ssl/certs/ca-certificates.crt",
        "etc/ssl/cert.pem",
    )

    /** Concatenates every readable system certificate, or null when none can be read. */
    fun bundle(): String? {
        val certificates = StringBuilder()
        systemStores.forEach { store ->
            File(store).listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (!file.isFile) return@forEach
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                if ("BEGIN CERTIFICATE" !in text) return@forEach
                certificates.append(text.trim()).append('\n')
            }
        }
        return certificates.takeIf { it.isNotEmpty() }?.toString()
    }

    /** Writes the bundle only when the rootfs has no anchors yet; true when a file was written. */
    fun installIfMissing(rootfs: File): Boolean {
        if (!rootfs.isDirectory) return false
        if (guestBundles.any { File(rootfs, it).isFile }) return false
        val content = bundle() ?: return false
        var written = false
        guestBundles.forEach { relative ->
            val target = File(rootfs, relative)
            if (target.isFile) return@forEach
            target.parentFile?.mkdirs()
            runCatching {
                target.writeText(content)
                written = true
            }
        }
        return written
    }
}
