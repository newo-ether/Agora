package com.newoether.agora.sandbox

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Extracts guest links unchanged; every host write goes through guest-aware parent resolution. */
internal fun extractTarEntries(
    tar: TarArchiveInputStream,
    destDir: File,
    checkActive: () -> Unit = {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Rootfs extraction cancelled")
    },
) {
    check(destDir.isDirectory || destDir.mkdirs()) { "Cannot create rootfs directory" }
    // Empty mount paths disable bind mounts while extracting the rootfs itself.
    val resolver = SandboxPathResolver(destDir, destDir, "", "") { null }
    val hardLinks = linkedMapOf<String, String>()
    val buffer = ByteArray(64 * 1024)

    fun safeArchivePath(name: String): String? {
        // Tar member and hard-link names are root-relative, unlike symlink targets.
        if (name.startsWith('/') || '\\' in name || '\u0000' in name || ':' in name) return null
        val components = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (components.any { it == ".." }) return null
        return "/" + components.joinToString("/")
    }

    fun outputFile(path: String): File {
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        val parent = resolver.resolvePath(parentPath)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create archive parent: $parentPath" }
        return File(parent, path.substringAfterLast('/'))
    }

    fun removeNonDirectory(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        require(!Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
            "Archive entry conflicts with a directory: $file"
        }
        Files.delete(file.toPath())
    }

    var entry = tar.nextEntry
    while (entry != null) {
        checkActive()
        val path = safeArchivePath(entry.name)
        if (path != null && path != "/") {
            hardLinks.remove(path)
            when {
                entry.isDirectory -> {
                    val directory = resolver.resolvePath(path)
                    check(directory.isDirectory || directory.mkdirs()) {
                        "Cannot create archive directory: ${entry.name}"
                    }
                }
                entry.isSymbolicLink -> {
                    val target = entry.linkName
                    require(target.isNotEmpty() && '\u0000' !in target && '\\' !in target && ':' !in target) {
                        "Invalid archive symbolic link: ${entry.name}"
                    }
                    val outFile = outputFile(path)
                    removeNonDirectory(outFile)
                    Files.createSymbolicLink(outFile.toPath(), Paths.get(target))
                }
                entry.isLink -> {
                    val target = safeArchivePath(entry.linkName)
                        ?: throw IOException("Invalid archive hard link: ${entry.name}")
                    val outFile = outputFile(path)
                    removeNonDirectory(outFile)
                    hardLinks[path] = target
                }
                entry.isFile -> {
                    val outFile = outputFile(path)
                    // Unlink first: replacing a symlink or hard link must never overwrite its target.
                    removeNonDirectory(outFile)
                    outFile.outputStream().use { output ->
                        while (true) {
                            checkActive()
                            val count = tar.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    outFile.setExecutable(entry.mode and 0x49 != 0, false)
                }
            }
        }
        entry = tar.nextEntry
    }

    // Tar hard links may precede their targets or refer to another deferred hard link.
    while (hardLinks.isNotEmpty()) {
        var progress = false
        val iterator = hardLinks.iterator()
        while (iterator.hasNext()) {
            checkActive()
            val (path, target) = iterator.next()
            val source = resolver.resolvePath(target)
            if (!Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS)) continue
            val outFile = outputFile(path)
            removeNonDirectory(outFile)
            // Android's app SELinux policy denies link(2), even inside app-private storage.
            // Keep these tar aliases usable as separate files with the same content and mode.
            Files.copy(source.toPath(), outFile.toPath(), COPY_ATTRIBUTES)
            iterator.remove()
            progress = true
        }
        if (!progress) throw IOException("Unresolved archive hard links: ${hardLinks.keys.joinToString()}")
    }
}
