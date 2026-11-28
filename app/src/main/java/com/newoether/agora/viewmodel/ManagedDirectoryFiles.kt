package com.newoether.agora.viewmodel

import java.io.File
import java.io.IOException

/** Descriptor-relative cleanup below a trusted app root, with no path-based fallback. */
internal class ManagedDirectoryFiles(filesDirectory: File) {
    // Android may expose filesDir through /data/user/0; resolve that trusted app-root alias once.
    private val filesPath = filesDirectory.canonicalPath

    fun step(directory: String): Int = safely { Native.step(filesPath, directory) }

    fun unlink(relativePath: String): Boolean {
        safely { Native.unlink(filesPath, relativePath) }
        return true
    }

    private fun safely(action: () -> Int): Int {
        val result = try {
            action()
        } catch (failure: LinkageError) {
            throw IOException("Safe attachment cleanup is unavailable", failure)
        }
        if (result < 0) throw IOException("Safe attachment cleanup failed (errno=${-result})")
        return result
    }

    private object Native {
        init { System.loadLibrary("agora_proot") }
        external fun step(filesDirectory: String, directory: String): Int
        external fun unlink(filesDirectory: String, relativePath: String): Int
    }
}
