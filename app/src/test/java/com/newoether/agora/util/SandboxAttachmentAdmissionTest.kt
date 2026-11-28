package com.newoether.agora.util

import com.newoether.agora.model.AttachmentStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SandboxAttachmentAdmissionTest {
    @Test
    fun capturedSandboxUploadCannotFallBackWhenSandboxBecomesUnavailable() {
        for (mime in listOf("text/plain", "image/png", "application/pdf", null)) {
            assertNull(FileValidator.inspectAttachment("source", "input", mime, 10, "sandbox", false))
            val admitted = FileValidator.inspectAttachment("source", "input", mime, 10, "sandbox", true)!!
            assertEquals(when (mime) { "image/png" -> "image"; "application/pdf" -> "pdf"; else -> "file" }, admitted.type)
            assertEquals(AttachmentStorage.LOCAL_SANDBOX_PENDING, admitted.storage)
        }
    }
}
