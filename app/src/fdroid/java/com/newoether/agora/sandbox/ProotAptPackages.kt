package com.newoether.agora.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CancellationException

/** Package transactions run inside the manager's existing rootfs mutation boundary. */
internal class ProotAptPackages(
    private val rootfsDir: File,
    private val execute: suspend (
        command: String,
        timeoutMs: Int,
        onOutput: ((String) -> Unit)?,
    ) -> SandboxManager.SandboxResult,
) {
    var lastError: String? = null

    private val paths = SandboxPathResolver(rootfsDir, rootfsDir, "", "") { null }
    private val packageName = Regex("[a-z0-9][a-z0-9+.-]+(?::[a-z0-9][a-z0-9-]*)?")
    private val apt = "env DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true " +
        "UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=l LC_ALL=C apt-get " +
        "-o Dpkg::Use-Pty=0 -o DPkg::Options::=--force-confdef " +
        "-o DPkg::Options::=--force-confold -o APT::Sandbox::User=root " +
        "-o APT::Get::AutomaticRemove=false"

    fun prepare() = prepare(rootfsDir)

    fun list(): List<SandboxManager.PackageInfo> = try {
        readInstalled(rootfsDir).map { it.info }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        lastError = error.message ?: "Cannot read dpkg status"
        emptyList()
    }

    suspend fun install(name: String, onProgress: (String) -> Unit = {}): Boolean {
        lastError = null
        val requested = validate(name, onProgress) ?: return false
        return try {
            prepare()
            if (!update(onProgress)) return false
            val operand = operand(requested)
            val available = execute(
                "env LC_ALL=C apt-cache show --no-all-versions -- ${quote(operand)}", 60_000, null,
            )
            val exact = available.stdout.lineSequence().any {
                it.startsWith("Package:") && it.substringAfter(':').trim() == requested.substringBefore(':')
            }
            if (available.exitCode != 0 || !exact) {
                return fail(available, "Package not found: $requested", onProgress)
            }
            val result = run("--assume-yes --no-remove install -- ${quote(operand)}", 300_000, onProgress)
            if (result.exitCode != 0) return fail(result, "Install failed", onProgress)
            if (readInstalled(rootfsDir).none { matches(requested, it.info.name) }) {
                lastError = "Package was not installed: $requested"
                onProgress("FAIL: $lastError")
                return false
            }
            true
        } catch (error: Exception) {
            failure(error, onProgress)
            false
        }
    }

    suspend fun remove(name: String, onProgress: (String) -> Unit = {}): Boolean {
        lastError = null
        val requested = validate(name, onProgress) ?: return false
        return try {
            prepare()
            val installed = readInstalled(rootfsDir, includeUnconfigured = true)
            if (installed.none { matches(requested, it.info.name) }) return true
            val baseFile = paths.resolvePath("/etc/agora/apt-base-packages")
            val base = if (baseFile.isFile) baseFile.readLines().toSet() else emptySet()
            val protectedPackages = installed.filter { it.essential || it.info.name in base }.map { it.info.name }.toSet()
            if (protectedPackages.any { matches(requested, it) }) {
                lastError = "Refusing to remove base or essential package: $requested"
                onProgress("FAIL: $lastError")
                return false
            }
            // apt may remove reverse dependencies. Check its complete plan against the base userland.
            val plan = run("--simulate remove -- ${quote(operand(requested))}", 60_000, onProgress)
            if (plan.exitCode != 0) return fail(plan, "Cannot resolve removal", onProgress)
            val removals = plan.stdout.lineSequence().filter { it.startsWith("Remv ") }
                .map { it.removePrefix("Remv ").substringBefore(' ') }.toSet()
            if (removals.none { matches(requested, it) }) {
                lastError = "Cannot verify removal plan for: $requested"
                onProgress("FAIL: $lastError")
                return false
            }
            val blocked = removals.filter { removal -> protectedPackages.any { matches(removal, it) } }
            if (blocked.isNotEmpty()) {
                lastError = "Removal would remove base or essential packages: ${blocked.joinToString(", ")}"
                onProgress("FAIL: $lastError")
                return false
            }
            val result = run("--assume-yes remove -- ${quote(operand(requested))}", 300_000, onProgress)
            if (result.exitCode != 0) return fail(result, "Removal failed", onProgress)
            if (readInstalled(rootfsDir, includeUnconfigured = true).any { matches(requested, it.info.name) }) {
                lastError = "Package was not removed: $requested"
                onProgress("FAIL: $lastError")
                return false
            }
            true
        } catch (error: Exception) {
            failure(error, onProgress)
            false
        }
    }

    /** A partial transaction reports its observed version changes and retains its failure in lastError. */
    suspend fun upgrade(onProgress: (String) -> Unit = {}): Int {
        lastError = null
        val before = try {
            prepare()
            readInstalled(rootfsDir).associate { it.info.name to it.info.version }
        } catch (error: Exception) {
            failure(error, onProgress)
            return 0
        }
        try {
            if (!update(onProgress)) return 0
            val result = run("--assume-yes --no-remove upgrade", 600_000, onProgress)
            if (result.exitCode != 0) fail(result, "Upgrade failed", onProgress)
        } catch (error: Exception) {
            failure(error, onProgress)
        }
        return try {
            readInstalled(rootfsDir, includeUnconfigured = true).count { entry ->
                val previous = before[entry.info.name]
                previous != null && previous != entry.info.version
            }
        } catch (error: Exception) {
            failure(error, onProgress)
            0
        }
    }

    private suspend fun update(onProgress: (String) -> Unit): Boolean {
        onProgress("Fetching package index...")
        // apt otherwise accepts stale indexes after some acquisition failures with exit code zero.
        val result = run("-o APT::Update::Error-Mode=any update", 300_000, onProgress)
        return result.exitCode == 0 || fail(result, "Package index update failed", onProgress)
    }

    private suspend fun run(
        arguments: String,
        timeoutMs: Int,
        onProgress: (String) -> Unit,
    ): SandboxManager.SandboxResult {
        // apt prints one line per acquired file; forwarding each line as it arrives is what
        // turns the terminal from a post-run dump into a live log.
        val lines = SandboxOutputLines(onProgress)
        val result = try {
            execute("$apt $arguments", timeoutMs, lines::accept)
        } finally {
            lines.flush()
        }
        onProgress("apt-get exit code: ${result.exitCode}")
        return result
    }

    private fun fail(result: SandboxManager.SandboxResult, fallback: String, onProgress: (String) -> Unit): Boolean {
        lastError = result.stderr.ifBlank { result.stdout }.ifBlank { "$fallback (exit ${result.exitCode})" }
        onProgress("FAIL: $lastError")
        return false
    }

    private fun failure(error: Exception, onProgress: (String) -> Unit) {
        if (error is CancellationException) throw error
        lastError = error.message ?: error.javaClass.simpleName
        onProgress("FAIL: $lastError")
    }

    private fun validate(name: String, onProgress: (String) -> Unit): String? {
        val trimmed = name.trim()
        if (packageName.matches(trimmed)) return trimmed
        lastError = "Invalid package name: $name"
        onProgress("FAIL: $lastError")
        return null
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // An explicit architecture disables apt's trailing +/- action shortcuts. Exact-name checks
    // above prevent apt-get's legacy regexp fallback for package names containing periods.
    private fun operand(name: String): String = if (':' in name) name else "$name:arm64"

    private fun matches(requested: String, installed: String): Boolean =
        requested == installed || (':' !in requested && requested == installed.substringBefore(':')) ||
            (':' !in installed && requested.substringBefore(':') == installed && requested.endsWith(":arm64"))

    private data class Installed(val info: SandboxManager.PackageInfo, val essential: Boolean)

    companion object {
        /** Called on staging before publication and before any legacy rootfs admits a shell command. */
        fun prepare(rootfsDir: File) {
            if (!rootfsDir.isDirectory) return
            val paths = SandboxPathResolver(rootfsDir, rootfsDir, "", "") { null }
            if (!paths.resolvePath("/var/lib/dpkg/status").isFile) return
            val baseFile = paths.resolvePath("/etc/agora/apt-base-packages")
            if (!baseFile.exists()) {
                val installed = readInstalled(rootfsDir, includeUnconfigured = true)
                check(baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                    "Cannot create APT base metadata directory"
                }
                baseFile.writeText(installed.joinToString("\n", postfix = "\n") { it.info.name })
            }
            // Resolve the parent in the guest, and preserve any user-provided policy entry.
            val policyParent = paths.resolvePath("/usr/sbin")
            val policy = File(policyParent, "policy-rc.d")
            if (!Files.exists(policy.toPath(), NOFOLLOW_LINKS)) {
                check(policyParent.isDirectory || policyParent.mkdirs()) { "Cannot create /usr/sbin" }
                policy.writeText("#!/bin/sh\nexit 101\n")
                check(policy.setExecutable(true)) { "Cannot make policy-rc.d executable" }
            }
        }

        private fun readInstalled(rootfsDir: File, includeUnconfigured: Boolean = false): List<Installed> {
            val paths = SandboxPathResolver(rootfsDir, rootfsDir, "", "") { null }
            val statusFile = paths.resolvePath("/var/lib/dpkg/status")
            check(statusFile.isFile) { "dpkg status database not found" }
            val installed = mutableListOf<Installed>()
            val fields = linkedMapOf<String, String>()
            var previousField: String? = null
            val presentStates = if (includeUnconfigured) {
                setOf("installed", "unpacked", "half-configured", "half-installed", "triggers-awaited", "triggers-pending")
            } else setOf("installed")
            fun flush() {
                val status = fields["Status"].orEmpty().trim().split(Regex("\\s+"))
                val name = fields["Package"].orEmpty()
                val version = fields["Version"].orEmpty()
                val validErrorState = status.getOrNull(1) == "ok" ||
                    (includeUnconfigured && status.getOrNull(1) == "reinstreq")
                if (status.size == 3 && validErrorState && status[2] in presentStates &&
                    name.isNotBlank() && version.isNotBlank()) {
                    val architecture = fields["Architecture"].orEmpty()
                    val displayName = if (architecture.isNotBlank() && architecture !in setOf("arm64", "all")) {
                        "$name:$architecture"
                    } else name
                    val sizeKiB = fields["Installed-Size"]?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                    val description = fields["Description"].orEmpty().lineSequence()
                        .map { if (it == ".") "" else it }.joinToString("\n")
                    installed.add(Installed(
                        SandboxManager.PackageInfo(displayName, version, sizeKiB.coerceAtMost(Long.MAX_VALUE / 1024) * 1024, description),
                        fields["Essential"].equals("yes", true) || fields["Protected"].equals("yes", true),
                    ))
                }
                fields.clear()
                previousField = null
            }
            statusFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.isBlank() -> flush()
                        line.startsWith(' ') || line.startsWith('\t') -> previousField?.let { key ->
                            fields[key] = fields[key].orEmpty() + "\n" + line.substring(1)
                        }
                        ':' in line -> {
                            val key = line.substringBefore(':')
                            fields[key] = line.substringAfter(':').trimStart()
                            previousField = key
                        }
                        else -> previousField = null
                    }
                }
            }
            flush()
            return installed
        }
    }
}
