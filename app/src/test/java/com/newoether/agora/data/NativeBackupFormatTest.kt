package com.newoether.agora.data

import com.newoether.agora.data.local.ChatEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackupFormatTest {
    @Test
    fun conversationEntriesAreStableAndPathSafe() {
        assertEquals("conv/items/612f622e2e2f63.json", NativeBackupFormat.conversationEntry("a/b../c"))
        assertEquals(
            NativeBackupFormat.conversationEntry("conversation"),
            NativeBackupFormat.conversationEntry("conversation"),
        )
    }
    @Test
    fun versionPolicy_acceptsOnlyKnownNativeFormats() {
        assertFalse(NativeBackupFormat.isSupported(0))
        assertTrue(NativeBackupFormat.isSupported(1))
        assertTrue(NativeBackupFormat.isSupported(NativeBackupFormat.CURRENT_VERSION))
        assertFalse(NativeBackupFormat.isSupported(NativeBackupFormat.CURRENT_VERSION + 1))
    }

    @Test
    fun portableCompositeSettings_removeEveryCredential() {
        val shell = ShellDeviceConfig(
            name = "server",
            type = "ssh",
            apiKey = "conch-secret",
            sshPassword = "ssh-secret",
            sshHostKey = "public-host-pin",
        ).withoutSecrets()
        assertEquals("", shell.apiKey)
        assertEquals("", shell.sshPassword)
        assertEquals("public-host-pin", shell.sshHostKey)

        val mcp = McpServerConfig(
            name = "mcp",
            url = "https://example.test/mcp",
            headers = mapOf("Authorization" to "Bearer mcp-secret"),
            disabledTools = setOf("dangerous"),
        ).withoutSecrets()
        assertTrue(mcp.headers.isEmpty())
        assertEquals(setOf("dangerous"), mcp.disabledTools)

        val remote = EmbeddingModelConfig(
            name = "remote",
            type = EmbeddingModelType.REMOTE,
            remoteModelName = "embed",
            remoteBaseUrl = "https://example.test",
            remoteApiKey = "embedding-secret",
            localFilePath = "/data/user/0/private.gguf",
        ).asPortableRemoteConfig()
        requireNotNull(remote)
        assertEquals("", remote.remoteApiKey)
        assertEquals("", remote.localFilePath)

        val local = EmbeddingModelConfig(
            name = "local",
            type = EmbeddingModelType.LOCAL_ONNX,
            localFilePath = "/data/user/0/private.gguf",
        ).asPortableRemoteConfig()
        assertNull(local)

        val portableJson = Json.encodeToString(listOf(shell)) +
            Json.encodeToString(listOf(mcp)) +
            Json.encodeToString(listOf(remote))
        assertFalse(portableJson.contains("conch-secret"))
        assertFalse(portableJson.contains("ssh-secret"))
        assertFalse(portableJson.contains("mcp-secret"))
        assertFalse(portableJson.contains("embedding-secret"))
        assertFalse(portableJson.contains("/data/user/0"))
    }

    @Test
    fun secretsPayload_usesStableIdsAndContainsAllCredentialKinds() {
        val data = NativeBackupSecrets(
            proxyPassword = "proxy-secret",
            shellDevices = mapOf(
                "device-id" to ShellDeviceSecrets(
                    apiKey = "shell-key",
                    sshPassword = "shell-password",
                ),
            ),
            embeddingApiKeys = mapOf("embedding-id" to "embedding-key"),
            mcpHeaders = mapOf(
                "mcp-id" to mapOf("Authorization" to "Bearer mcp-key"),
            ),
        )
        val encoded = Json.encodeToString(data)
        assertTrue(encoded.contains("\"device-id\""))
        assertTrue(encoded.contains("proxy-secret"))
        assertTrue(encoded.contains("shell-password"))
        assertTrue(encoded.contains("embedding-key"))
        assertTrue(encoded.contains("mcp-key"))
    }

    @Test
    fun importedConversation_neverRestoresUnreadDeviceState() {
        val restored = sanitizeImportedConversation(
            conversation = ChatEntity(
                id = "conversation",
                title = "Title",
                hasUnreadGeneration = true,
            ),
            availableTaskIds = emptySet(),
        )
        assertFalse(restored.hasUnreadGeneration)
    }
}
