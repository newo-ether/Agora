package com.newoether.agora.sandbox

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Counts private regular-file bytes without following links into shared or external storage. */
internal suspend fun sandboxPrivateUsageBytes(rootfs: File, home: File): Long {
    val context = currentCoroutineContext()
    val shared = rootfs.toPath().toAbsolutePath().normalize().resolve("mnt/shared")
    var bytes = 0L
    for (directory in listOf(rootfs, home)) {
        val root = directory.toPath().toAbsolutePath().normalize()
        if (!Files.exists(root)) continue
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                return if (path == shared) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                if (attributes.isRegularFile) bytes += attributes.size()
                return FileVisitResult.CONTINUE
            }
        })
    }
    return bytes
}
