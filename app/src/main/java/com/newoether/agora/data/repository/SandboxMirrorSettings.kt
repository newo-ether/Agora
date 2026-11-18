package com.newoether.agora.data.repository

import com.newoether.agora.sandbox.SandboxMirror
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Typed, eagerly published view of the persisted sandbox mirror id. */
internal fun SettingsRepository.sandboxMirrorFlow(): StateFlow<SandboxMirror> =
    publication.hot(settingsManager.sandboxMirror.map { SandboxMirror.fromId(it) }, SandboxMirror.DEFAULT)

/** Persists the mirror choice; an installed guest is rewritten through the sandbox manager. */
fun SettingsRepository.setSandboxMirror(mirror: SandboxMirror) = scope.launch {
    settingsManager.saveSandboxMirror(mirror.id)
}
