package com.newoether.agora.tool

import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** The native boundary owns the atomic no-overwrite decision, including competing writers. */
internal object AtomicFilePublish {
    fun noReplace(staging: File, destination: File) {
        val error = try {
            Native.renameNoReplace(staging.path, destination.path)
        } catch (failure: LinkageError) {
            throw IOException("Atomic no-replace publication is unavailable; destination was not published", failure)
        }
        when (error) {
            0 -> return
            OsConstants.EEXIST -> throw FileAlreadyExistsException(destination.path, staging.path, "overwrite is false")
            OsConstants.ENOSYS, OsConstants.EOPNOTSUPP, OsConstants.EINVAL, OsConstants.EXDEV ->
                throw IOException("Atomic no-replace publication is unsupported for this destination (errno=$error)")
            else -> throw IOException("Atomic no-replace publication failed (errno=$error); destination was not published")
        }
    }

    // Endpoint construction and remote-only transfers must not load any native library.
    private object Native {
        init { System.loadLibrary("agora_proot") }
        external fun renameNoReplace(staging: String, destination: String): Int
    }
}
