package com.newoether.agora.sandbox

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Deletes only this tree's entries, including symlinks themselves, never their targets. */
internal fun deleteSandboxTree(directory: File) {
    val root = directory.toPath().toAbsolutePath().normalize()
    require(root.parent != null) { "Cannot delete a filesystem root" }
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        private fun requireInside(path: Path) {
            require(path.toAbsolutePath().normalize().startsWith(root)) {
                "Sandbox cleanup escaped its root: $path"
            }
        }

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            requireInside(dir)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            requireInside(file)
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            requireInside(dir)
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
