package com.newoether.agora.sandbox

/**
 * Source used for the sandbox rootfs archive and the guest package manager.
 *
 * Both choices point at the same upstream artifacts, so a download stays verifiable by the
 * pinned SHA-256 regardless of which mirror served it.
 */
enum class SandboxMirror(val id: String) {
    /** China-friendly mirrors: TUNA for Alpine/Ubuntu/APT, a GitHub proxy for Debian rootfs. */
    TUNA("tuna"),

    /** Upstream distribution hosts. */
    OFFICIAL("official"),
    ;

    companion object {
        val DEFAULT = OFFICIAL

        fun fromId(id: String?): SandboxMirror = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
