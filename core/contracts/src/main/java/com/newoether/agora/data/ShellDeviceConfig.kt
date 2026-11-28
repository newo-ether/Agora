package com.newoether.agora.data

import kotlinx.serialization.Serializable
import java.util.UUID

const val SSH_AUTH_PASSWORD = "password"
const val SSH_AUTH_PRIVATE_KEY = "private_key"
@Serializable
data class ShellDeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "conch",          // "conch" | "ssh"
    // Conch fields (type=conch)
    val serverUrl: String = "",
    val apiKey: String = "",
    val conchPublicKey: String = "",
    // SSH fields (type=ssh)
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    val sshAuthMode: String = SSH_AUTH_PASSWORD,
    val sshPrivateKey: String = "",
    val sshPrivateKeyName: String = "",
    val sshKeyPassphrase: String = "",
    // Pinned SSH host key (base64 of the server public-key blob). Blank = not yet
    // pinned (trust-on-first-use); once set, connections must match or are rejected.
    val sshHostKey: String = ""
)
