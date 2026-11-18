package com.newoether.agora.sandbox

/**
 * Package managers write arbitrary chunks: partial lines, CRLF endings and carriage-return
 * progress updates. The terminal shows whole lines, so chunks are split before they reach it.
 */
internal class SandboxOutputLines(private val emit: (String) -> Unit) {
    private val pending = StringBuilder()

    fun accept(chunk: String) {
        for (character in chunk) {
            when (character) {
                '\n', '\r' -> emitLine()
                else -> {
                    pending.append(character)
                    // A process that never ends its line must not grow memory without bound.
                    if (pending.length >= MAX_LINE_CHARS) emitLine()
                }
            }
        }
    }

    fun flush() {
        if (pending.isNotEmpty()) emitLine()
    }

    private fun emitLine() {
        if (pending.isEmpty()) return
        emit(pending.toString())
        pending.setLength(0)
    }

    private companion object {
        const val MAX_LINE_CHARS = 4096
    }
}
