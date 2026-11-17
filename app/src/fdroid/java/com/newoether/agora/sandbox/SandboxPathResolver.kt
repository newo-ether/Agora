package com.newoether.agora.sandbox

import com.newoether.agora.util.SHELL_FILE_SEARCH_SKIPPED_DIRS
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.ArrayDeque

internal data class ResolvedSandboxPath(
    val file: File,
    val physicalRoot: File,
    val virtualRoot: String,
)

internal class SandboxPathResolver(
    private val rootfsDir: File,
    private val homeMountDir: File,
    private val homeMountPath: String,
    private val sharedStorageMountPath: String,
    private val sharedStorageHostDir: () -> File?,
) {
    fun ensureSandboxMountTargets() {
        check(homeMountDir.isDirectory || homeMountDir.mkdirs()) { "Cannot create sandbox home" }
        // Resolve without bind mounts so an archive-provided parent link cannot make mkdirs
        // follow an absolute guest target on the Android host.
        val rootfsOnly = SandboxPathResolver(rootfsDir, rootfsDir, "", "") { null }
        for (mount in listOf(homeMountPath, sharedStorageMountPath).filter { it.isNotEmpty() }) {
            val target = rootfsOnly.resolvePath(mount)
            check(target.isDirectory || target.mkdirs()) { "Cannot create sandbox mount target: $mount" }
        }
    }

    fun resolveSandboxPath(path: String): ResolvedSandboxPath {
        val pending = ArrayDeque(normalizeVirtualPath(path).split('/').filter { it.isNotEmpty() })
        val components = mutableListOf<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            when (val component = pending.removeFirst()) {
                "." -> Unit
                ".." -> {
                    val current = mapVirtualPath(components)
                    require(components.isNotEmpty() && current.file != current.physicalRoot) {
                        "Path traversal: $path"
                    }
                    components.removeAt(components.lastIndex)
                }
                else -> {
                    require(component.indexOf('\u0000') < 0 && ':' !in component) {
                        "Invalid sandbox path: $path"
                    }
                    components.add(component)
                    val mapped = mapVirtualPath(components)
                    if (
                        Files.isSymbolicLink(mapped.file.toPath()) &&
                        !leadsToMount(components, pending)
                    ) {
                        require(++links <= 40) { "Too many symbolic links: $path" }
                        val target = Files.readSymbolicLink(mapped.file.toPath()).toString()
                            .replace(File.separatorChar, '/')
                        require(target.isNotEmpty() && '\u0000' !in target && '\\' !in target) {
                            "Invalid symbolic link: $path"
                        }
                        components.removeAt(components.lastIndex)
                        if (target.startsWith('/')) components.clear()
                        val targetParts = target.split('/').filter { it.isNotEmpty() }
                        for (part in targetParts.asReversed()) pending.addFirst(part)
                    }
                }
            }
        }
        return mapVirtualPath(components)
    }

    private fun leadsToMount(components: List<String>, pending: ArrayDeque<String>): Boolean {
        val current = "/" + components.joinToString("/")
        for (mount in listOf(homeMountPath, sharedStorageMountPath)) {
            if (mount.isEmpty() || !mount.startsWith("$current/")) continue
            val suffix = mount.removePrefix("$current/").split('/')
            if (pending.take(suffix.size) == suffix) return true
        }
        return false
    }

    private fun mapVirtualPath(components: List<String>): ResolvedSandboxPath {
        val virtualPath = "/" + components.joinToString("/")
        val virtualRoot: String
        val root: File
        when {
            isWithinMount(virtualPath, homeMountPath) -> {
                virtualRoot = homeMountPath
                root = homeMountDir.canonicalFile
            }
            isWithinMount(virtualPath, sharedStorageMountPath) -> {
                virtualRoot = sharedStorageMountPath
                root = sharedStorageHostDir()?.canonicalFile
                    ?: throw SecurityException(
                        "Shared storage is not mounted. Enable it and grant all-files access first.",
                    )
            }
            else -> {
                virtualRoot = "/"
                root = rootfsDir.canonicalFile
            }
        }
        // All guest components have been checked individually. Never canonicalize a guest
        // suffix: an absolute symlink belongs to the guest root, not the host filesystem.
        val suffix = virtualPath.removePrefix(virtualRoot).trimStart('/')
        return ResolvedSandboxPath(if (suffix.isEmpty()) root else File(root, suffix), root, virtualRoot)
    }

    private fun isWithinMount(path: String, mount: String): Boolean =
        mount.isNotEmpty() && (path == mount || path.startsWith("$mount/"))

    fun resolvePath(path: String): File = resolveSandboxPath(path).file

    // remaining: levels still allowed including the current dir's files. -1 = unlimited;
    // 1 = only this dir's files (no descent); >1 = descend with one fewer level.
    fun walkVirtualFiles(
        dir: File,
        result: MutableList<String>,
        physicalRootAbsPath: String,
        virtualRoot: String,
        remaining: Int = -1,
        virtualBasePath: String? = null,
    ) {
        val physicalRoot = File(physicalRootAbsPath).toPath().toAbsolutePath().normalize()
        val start = dir.toPath().toAbsolutePath().normalize()
        require(start.startsWith(physicalRoot)) { "Search root is outside the sandbox" }
        val relative = physicalRoot.relativize(start).toString().replace(File.separatorChar, '/')
        val prefix = if (virtualRoot == "/") "" else virtualRoot.trimEnd('/')
        val startPath = virtualBasePath?.let(::normalizeVirtualPath)
            ?: "$prefix/$relative".trimEnd('/').ifEmpty { "/" }
        val ancestors = mutableSetOf<String>()

        fun walk(virtualPath: String, levels: Int) {
            val resolved = try {
                resolvePath(virtualPath)
            } catch (_: Exception) {
                return
            }
            if (!resolved.isDirectory) return
            if (!ancestors.add(resolved.absolutePath)) return
            try {
                for (child in resolved.listFiles().orEmpty()) {
                    val childPath = "${virtualPath.trimEnd('/')}/${child.name}"
                    val target = try {
                        resolvePath(childPath)
                    } catch (_: Exception) {
                        continue
                    }
                    if (target.isDirectory) {
                        if (child.name !in SHELL_FILE_SEARCH_SKIPPED_DIRS && (levels < 0 || levels > 1)) {
                            walk(childPath, if (levels < 0) -1 else levels - 1)
                        }
                    } else if (Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS)) {
                        result.add(childPath)
                    }
                }
            } finally {
                ancestors.remove(resolved.absolutePath)
            }
        }
        walk(startPath, remaining)
    }
}
