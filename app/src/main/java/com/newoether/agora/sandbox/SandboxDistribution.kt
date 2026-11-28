package com.newoether.agora.sandbox

/** Supported arm64 userlands; the selected environment shares Agora's separate home mount. */
enum class SandboxDistribution(
    val id: String,
    val displayName: String,
    val versionLabel: String,
    val usesApt: Boolean,
    val quickPackages: List<String>,
) {
    ALPINE(
        id = "alpine",
        displayName = "Alpine",
        versionLabel = "3.21",
        usesApt = false,
        quickPackages = listOf("python3", "py3-pip", "nodejs", "npm", "git", "curl", "wget", "openssh", "build-base", "htop"),
    ),
    DEBIAN(
        id = "debian",
        displayName = "Debian",
        versionLabel = "13 (trixie)",
        usesApt = true,
        quickPackages = listOf("python3", "python3-pip", "nodejs", "npm", "git", "curl", "wget", "openssh-client", "build-essential", "htop"),
    ),
    UBUNTU(
        id = "ubuntu",
        displayName = "Ubuntu",
        versionLabel = "24.04 LTS (Noble)",
        usesApt = true,
        quickPackages = listOf("python3", "python3-pip", "nodejs", "npm", "git", "curl", "wget", "openssh-client", "build-essential", "htop"),
    );

    companion object {
        /** Existing installations without a stored selection remain Alpine. */
        fun fromId(id: String?): SandboxDistribution = entries.firstOrNull { it.id == id } ?: ALPINE
    }
}
