package com.newoether.agora.sandbox

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Alpine's existing host-side downloads and APK world stay under the manager's mutation boundary. */
internal class ProotAlpinePackages(
    private val rootfsDir: File,
    private val cacheDir: File,
    private val execute: suspend (command: String, timeoutMs: Int) -> SandboxManager.SandboxResult,
) {
    var lastError: String? = null
    private val metadata = AlpinePackageMetadataStore(rootfsDir)
    private val mirror = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main"

    fun prepare(forceBaseWorld: Boolean = false) {
        if (forceBaseWorld) {
            metadata.captureBaseWorld(force = true)
            metadata.writeExplicitPackages(emptySet())
        }
        metadata.ensurePackageMetadata()
    }

    fun list(): List<SandboxManager.PackageInfo> = try {
        val db = File(rootfsDir, "lib/apk/db/installed")
        val packages = mutableListOf<SandboxManager.PackageInfo>()
        var name = ""
        var version = ""
        var description = ""
        fun flush() {
            if (name.isNotBlank()) packages.add(SandboxManager.PackageInfo(name, version, description = description))
            name = ""
            version = ""
            description = ""
        }
        if (db.isFile) db.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("P:") -> name = line.substring(2).trim()
                    line.startsWith("V:") -> version = line.substring(2).trim()
                    line.startsWith("T:") -> description = line.substring(2).trim()
                    line.isBlank() -> flush()
                }
            }
        }
        flush()
        packages
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        lastError = error.message
        emptyList()
    }

    suspend fun install(name: String, onProgress: (String) -> Unit = {}): Boolean {
        lastError = null
        val requested = validate(name, onProgress) ?: return false
        try {
            prepare()
            val installed = metadata.readInstalledVersions()
            val (packages, providers) = fetchIndex(installed.keys, onProgress)
            if (requested !in packages) {
                lastError = "Not found: $requested"
                onProgress("FAIL: package '$requested' not found in index")
                return false
            }
            val changes = collectAlpinePackageChanges(listOf(requested), packages, providers, installed)
            onProgress("${changes.size} packages to install")
            if (changes.isEmpty()) {
                metadata.addExplicitPackage(requested)
                onProgress("$requested is already installed and up to date.")
                return true
            }
            applyChanges(changes, packages, 120_000, onProgress)
            // Preserve Alpine's behavior for nonzero post-install scripts: installed DB is authoritative.
            if (requested !in metadata.readInstalledVersions()) {
                if (lastError == null) lastError = "Package was not installed: $requested"
                return false
            }
            lastError = null
            metadata.addExplicitPackage(requested)
            return true
        } catch (error: Exception) {
            failure(error, onProgress)
            return false
        }
    }

    suspend fun remove(name: String, onProgress: (String) -> Unit = {}): Boolean {
        lastError = null
        val requested = validate(name, onProgress) ?: return false
        try {
            prepare()
            val installed = metadata.readInstalledVersions()
            onProgress("DB has package: ${requested in installed}")
            if (requested !in installed) {
                val explicit = metadata.readExplicitPackages().apply { remove(requested) }
                metadata.writeExplicitPackages(explicit)
                metadata.normalizeWorld(explicit)
                return true
            }
            if (metadata.isBasePackage(requested)) {
                lastError = "Refusing to remove base package: $requested"
                onProgress(lastError.orEmpty())
                return false
            }
            val previous = metadata.readExplicitPackages()
            val next = previous.toMutableSet().apply { remove(requested) }.toSet()
            metadata.writeExplicitPackages(next)
            metadata.normalizeWorld(next)
            var removed = false
            try {
                onProgress("Running: apk del $requested")
                val result = execute("apk del ${quote(requested)}", 60_000)
                report(result, onProgress)
                removed = requested !in metadata.readInstalledVersions()
                if (!removed) {
                    lastError = result.stderr.ifBlank { result.stdout }.ifBlank { "Package was not removed: $requested" }
                    return false
                }
                metadata.normalizeWorld()
                return true
            } finally {
                if (!removed) {
                    metadata.writeExplicitPackages(previous)
                    metadata.normalizeWorld(previous)
                }
            }
        } catch (error: Exception) {
            failure(error, onProgress)
            return false
        }
    }

    suspend fun upgrade(onProgress: (String) -> Unit = {}): Int {
        lastError = null
        try {
            prepare()
            val installed = metadata.readInstalledVersions()
            val (packages, providers) = fetchIndex(installed.keys, onProgress)
            val targets = installed.filter { (name, version) ->
                packages[name]?.let { compareAlpineVersions(it.version, version) > 0 } == true
            }.keys
            if (targets.isEmpty()) {
                onProgress("All packages up to date.")
                return 0
            }
            val changes = collectAlpinePackageChanges(targets, packages, providers, installed)
            onProgress("${changes.size} packages to upgrade")
            val result = applyChanges(changes, packages, 300_000, onProgress)
            metadata.normalizeWorld()
            val after = metadata.readInstalledVersions()
            val upgraded = targets.count { name ->
                val beforeVersion = installed[name]
                val afterVersion = after[name]
                beforeVersion != null && afterVersion != null && compareAlpineVersions(afterVersion, beforeVersion) > 0
            }
            if (result.exitCode == 0 || upgraded > 0) lastError = null
            return upgraded
        } catch (error: Exception) {
            failure(error, onProgress)
            return 0
        }
    }

    private suspend fun fetchIndex(
        installed: Set<String>,
        onProgress: (String) -> Unit,
    ): Pair<Map<String, FullPkgEntry>, Map<String, String>> {
        onProgress("Fetching package index...")
        cacheDir.mkdirs()
        val index = File(cacheDir, "APKINDEX.tar.gz")
        try {
            download("$mirror/aarch64/APKINDEX.tar.gz", index, onProgress)
            return parseFullApkIndex(index, preferredPackages = installed)
        } finally {
            index.delete()
        }
    }

    private suspend fun applyChanges(
        changes: Set<String>,
        packages: Map<String, FullPkgEntry>,
        timeoutMs: Int,
        onProgress: (String) -> Unit,
    ): SandboxManager.SandboxResult {
        val tmpDir = File(rootfsDir, "tmp/agora-apk-${java.util.UUID.randomUUID()}")
        check(tmpDir.mkdirs()) { "Cannot create package staging directory" }
        cacheDir.mkdirs()
        val staged = mutableListOf<File>()
        try {
            for (name in changes) {
                currentCoroutineContext().ensureActive()
                val version = packages.getValue(name).version
                val filename = "$name-$version.apk"
                val cached = File(cacheDir, filename)
                if (!cached.exists() || cached.length() == 0L) {
                    onProgress("Downloading $filename...")
                    try {
                        download("$mirror/aarch64/$filename", cached, onProgress)
                    } catch (error: Exception) {
                        cached.delete()
                        throw error
                    }
                }
                val destination = File(tmpDir, filename)
                staged.add(destination)
                cached.copyTo(destination, overwrite = true)
                cached.delete()
            }
            onProgress("Installing ${staged.size} packages...")
            currentCoroutineContext().ensureActive()
            val result = execute(
                "apk add --allow-untrusted --no-network ${staged.joinToString(" ") { quote("/tmp/${tmpDir.name}/${it.name}") }}",
                timeoutMs,
            )
            report(result, onProgress)
            if (result.exitCode != 0) lastError = result.stderr.ifBlank { result.stdout }.ifBlank { "Upgrade failed" }
            return result
        } finally {
            // Only this transaction's archives are temporary; /tmp may contain user files.
            tmpDir.deleteRecursively()
        }
    }

    private suspend fun download(url: String, destination: File, onProgress: (String) -> Unit) {
        currentCoroutineContext().ensureActive()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            onProgress("Connecting to ${connection.url.host}...")
            val code = connection.responseCode
            onProgress("HTTP $code (${connection.contentLengthLong} bytes)")
            check(code == 200) { "HTTP $code from $url" }
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun report(result: SandboxManager.SandboxResult, onProgress: (String) -> Unit) {
        if (result.stdout.isNotBlank()) onProgress(result.stdout)
        if (result.stderr.isNotBlank()) onProgress(result.stderr)
        onProgress("apk exit code: ${result.exitCode}")
    }

    private fun validate(name: String, onProgress: (String) -> Unit): String? = try {
        metadata.sanitizePackageName(name)
    } catch (error: IllegalArgumentException) {
        lastError = error.message
        onProgress("FAIL: $lastError")
        null
    }

    private fun failure(error: Exception, onProgress: (String) -> Unit) {
        if (error is CancellationException) throw error
        lastError = error.message ?: error.javaClass.simpleName
        onProgress("FAIL: $lastError")
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
