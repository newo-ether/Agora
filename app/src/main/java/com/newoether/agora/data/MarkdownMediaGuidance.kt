package com.newoether.agora.data

/**
 * Shared editable text that teaches the model which path spelling turns into a picture, a player
 * card or a tappable file. It must match what `resolveMarkdownDestination` accepts on the phone.
 */
object MarkdownMediaGuidance {
    private const val HEADING = "## Media links"

    val text: String = "\n\n" + """
        $HEADING
        - Show a local picture or video as a Markdown image with the file's absolute path: `![preview](/storage/emulated/0/DCIM/a.jpg)`. An image extension renders a preview, a video extension renders a player card, and a bare path in prose is tappable too.
        - Write the same paths the shell tools see: `/storage/emulated/0/...` (also `/sdcard/...`), `/mnt/shared/...`, and `/home/agora/...` for the Local Sandbox home. A `file://` URL over one of them is accepted.
        - Encode a space as `%20`, or wrap the whole destination in angle brackets: `![preview](</storage/emulated/0/My Files/a b.png>)`.
        - Use `shell://SERVER/absolute/path` only for a device other than this phone; a local path stays local even when the target is the Local Sandbox.
        - An external SD card path such as `/storage/1A2B-3C4D/...` is not readable; copy the file into built-in storage first.
        - Any other local file, such as a PDF or an archive, becomes a link that downloads before it opens.
    """.trimIndent()

    fun item(): PromptTemplateItem = PromptTemplateItem(type = PromptItemType.CUSTOM, value = text)
}
