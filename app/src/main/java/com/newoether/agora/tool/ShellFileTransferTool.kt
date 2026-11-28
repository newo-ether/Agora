package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.util.ShellClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

internal fun fileTransferDefinition(): ToolDefinition = ToolDefinition(function = ToolFunction(
    name = "file_transfer",
    description = "Copy one binary or text file between Local Sandbox and Conch shell servers, or on the same server. " +
        "Maximum 1GiB; destination parent must exist. Source is kept. Overwrite defaults to false and is checked atomically. " +
        "Returns actual paths, byte count and verified SHA-256; file contents do not enter model context. SSH servers are not supported.",
    parameters = ToolParameters(
        properties = mapOf(
            "source_server" to ToolProperty("string", "Exact source shell server name; use shell_list."),
            "source_path" to ToolProperty("string", "Absolute source file path."),
            "destination_server" to ToolProperty("string", "Exact destination shell server name; use shell_list."),
            "destination_path" to ToolProperty("string", "Absolute destination file path; parent directory must exist."),
            "overwrite" to ToolProperty("boolean", "Replace an existing regular destination file atomically (default false)."),
        ),
        required = listOf("source_server", "source_path", "destination_server", "destination_path"),
    ),
))

internal suspend fun executeShellFileTransfer(
    arguments: String,
    ctx: GenerationContext,
    sandbox: SandboxManager?,
    confirm: suspend (ShellDeviceConfig?, String) -> Boolean,
): String {
    val args = parseToolArgs(arguments)
    val sourceName = arg(args, "source_server")
    val destinationName = arg(args, "destination_server")
    val sourcePath = arg(args, "source_path")
    val destinationPath = arg(args, "destination_path")
    if (listOf(sourceName, destinationName, sourcePath, destinationPath).any { it.isBlank() }) {
        return jsonError("file_transfer", "source_server, source_path, destination_server and destination_path are required")
    }
    if (!ctx.shellEnabled) return jsonError("file_transfer", "Shell tools are disabled")
    val rawOverwrite = args["overwrite"]
    if (rawOverwrite != null && (rawOverwrite !is JsonPrimitive || rawOverwrite.isString ||
            rawOverwrite.content !in setOf("true", "false"))) {
        return jsonError("file_transfer", "overwrite must be a boolean")
    }
    fun device(name: String): ShellDeviceConfig? = ctx.shellDevices.singleOrNull { it.name.equals(name, true) }
    fun endpoint(name: String): FileTransferEndpoint {
        if (name.equals("Local Sandbox", true)) {
            check(ctx.sandboxEnabled && sandbox != null) { "Local Sandbox is disabled or unavailable" }
            return FileTransferEndpoint("Local Sandbox", sandbox = sandbox)
        }
        val remote = device(name) ?: error("Unknown or ambiguous server: $name; use shell_list")
        check(remote.type != "ssh") { "file_transfer supports Local Sandbox and Conch; SSH server $name is unsupported" }
        return FileTransferEndpoint(remote.name, client = ShellClient(remote.serverUrl.trimEnd('/'), remote.apiKey, remote.conchPublicKey))
    }
    return try {
        val source = endpoint(sourceName)
        val destination = endpoint(destinationName)
        if (!confirm(device(destinationName), "copy $sourceName:$sourcePath to $destinationName:$destinationPath")) {
            return jsonError("file_transfer", "denied_by_user: the user declined this file transfer")
        }
        transferShellFile(source, sourcePath, destination, destinationPath, boolArg(args, "overwrite"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        jsonError("file_transfer", failure.message ?: "File transfer failed")
    }
}
