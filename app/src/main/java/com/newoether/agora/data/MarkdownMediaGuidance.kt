package com.newoether.agora.data

/**
 * Shared editable text that teaches the model which path spelling turns into a picture, a player
 * card or a tappable file. It must match what `resolveMarkdownDestination` accepts on the phone.
 */
object MarkdownMediaGuidance {
    private const val HEADING = "## Media links"

    val text: String = "\n\n" + """
        $HEADING
        - Embed an image, video, audio, or HTML preview file with Markdown image syntax: `![label](shell://Local%20Sandbox/home/agora/a.png)` or `![label](/storage/emulated/0/DCIM/a.jpg)`. Supported extensions render inline: images (`png`, `jpg`, `jpeg`, `gif`, `webp`, `bmp`, `avif`), video (`mp4`, `m4v`, `webm`, `mov`, `mkv`, `avi`, `3gp`, `ogv`), audio (`mp3`, `wav`, `ogg`, `oga`, `opus`, `m4a`, `aac`, `flac`, `amr`), and local or `shell://` HTML files (`html`, `htm`, rendered inside an interactive preview card). A bare path or `shell://` link in prose is tappable too.
        - For files on any shell server, including Local Sandbox and Conch servers (such as Termux `/data/data/com.termux/...` or sandbox paths like `/home/agora/...` and `/tmp/...`), prefer `shell://SERVER/absolute/path` (for example `shell://Local%20Sandbox/home/agora/out.mp3`).
        - Bare absolute paths and `file://` URLs are accepted ONLY under phone-readable roots: `/storage/emulated/0/...` (or `/sdcard/...`, `/storage/self/primary/...`), `/mnt/shared/...`, and `/home/agora/...`. Any other bare path (such as `/data/data/...`, `/tmp/...`, or external SD cards like `/storage/1A2B-3C4D/...`) cannot be read directly; use `shell://SERVER/absolute/path` or copy the file into `/storage/emulated/0/...` first.
        - Encode spaces as `%20`, or wrap the whole destination in angle brackets: `![label](<shell://Local Sandbox/home/agora/My Files/a b.png>)`.
        - Any other file (such as a PDF or archive) stays tappable as a link: phone-readable files open in place, while other `shell://` files are copied into the app cache before opening.
    """.trimIndent()

    /** Shipped media guidance text after audio support, before embedded HTML files were added. */
    internal val previousTextBeforeHtmlFileForMigration: String = "\n\n" + """
        $HEADING
        - Embed an image, video, or audio file with Markdown image syntax: `![label](shell://Local%20Sandbox/home/agora/a.png)` or `![label](/storage/emulated/0/DCIM/a.jpg)`. Supported extensions render inline: images (`png`, `jpg`, `jpeg`, `gif`, `webp`, `bmp`, `avif`), video (`mp4`, `m4v`, `webm`, `mov`, `mkv`, `avi`, `3gp`, `ogv`), and audio (`mp3`, `wav`, `ogg`, `oga`, `opus`, `m4a`, `aac`, `flac`, `amr`). A bare path or `shell://` link in prose is tappable too.
        - For files on any shell server, including Local Sandbox and Conch servers (such as Termux `/data/data/com.termux/...` or sandbox paths like `/home/agora/...` and `/tmp/...`), prefer `shell://SERVER/absolute/path` (for example `shell://Local%20Sandbox/home/agora/out.mp3`).
        - Bare absolute paths and `file://` URLs are accepted ONLY under phone-readable roots: `/storage/emulated/0/...` (or `/sdcard/...`, `/storage/self/primary/...`), `/mnt/shared/...`, and `/home/agora/...`. Any other bare path (such as `/data/data/...`, `/tmp/...`, or external SD cards like `/storage/1A2B-3C4D/...`) cannot be read directly; use `shell://SERVER/absolute/path` or copy the file into `/storage/emulated/0/...` first.
        - Encode spaces as `%20`, or wrap the whole destination in angle brackets: `![label](<shell://Local Sandbox/home/agora/My Files/a b.png>)`.
        - Any other file (such as a PDF or archive) stays tappable as a link: phone-readable files open in place, while other `shell://` files are copied into the app cache before opening.
    """.trimIndent()

    /** Shipped media guidance text before audio support and explicit root restrictions were added. */
    internal val previousTextBeforeAudioForMigration: String = "\n\n" + """
        $HEADING
        - Show a local picture or video as a Markdown image with the file's absolute path: `![preview](/storage/emulated/0/DCIM/a.jpg)`. An image extension renders a preview, a video extension renders a player card, and a bare path in prose is tappable too.
        - Write the same paths the shell tools see: `/storage/emulated/0/...` (also `/sdcard/...`), `/mnt/shared/...`, and `/home/agora/...` for the Local Sandbox home. A `file://` URL over one of them is accepted.
        - Encode a space as `%20`, or wrap the whole destination in angle brackets: `![preview](</storage/emulated/0/My Files/a b.png>)`.
        - Use `shell://SERVER/absolute/path` only for a device other than this phone; a local path stays local even when the target is the Local Sandbox.
        - An external SD card path such as `/storage/1A2B-3C4D/...` is not readable; copy the file into built-in storage first.
        - Any other file, such as a PDF or an archive, stays tappable as a link. A phone-local file opens in place; a file on another shell device is copied into the app cache before it opens.
    """.trimIndent()

    fun item(): PromptTemplateItem = PromptTemplateItem(type = PromptItemType.CUSTOM, value = text)

    internal fun previousItemBeforeHtmlFileForMigration(): PromptTemplateItem =
        PromptTemplateItem(type = PromptItemType.CUSTOM, value = previousTextBeforeHtmlFileForMigration)

    internal fun previousItemBeforeAudioForMigration(): PromptTemplateItem =
        PromptTemplateItem(type = PromptItemType.CUSTOM, value = previousTextBeforeAudioForMigration)
}
