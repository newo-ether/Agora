package com.newoether.agora.data

/** Shared editable text for the shipped template and the editor's explicit insert action. */
object HtmlPreviewGuidance {
    private const val HEADING = "## Interactive HTML preview"

    val text: String = "\n\n" + """
        $HEADING
        - Use a closed `html-preview` fenced block for an interactive visualization in native Agora chat; it renders automatically only after the closing fence. An `html` fence shows source with a manual Run action.
        - To embed an existing local, Local Sandbox, or Conch `.html`/`.htm` file directly as a preview card (up to 1 MB / 512 KB source), use Markdown image syntax `![title](shell://SERVER/absolute/path.html)` or `![title](/storage/emulated/0/Download/page.html)`, or put that single path inside a closed `html-preview` fence. Remote `http(s)://` HTML URLs stay ordinary links.
        - Keep each document self-contained with inline HTML, CSS, JavaScript, and data. HTTPS static JS/CSS/fonts may load only from cdnjs.cloudflare.com, cdn.jsdelivr.net, unpkg.com, or esm.sh, with paths ending in .js, .mjs, .css, .woff, .woff2, or .ttf. Use exact versioned URLs without query strings or redirects.
        - Maps may load numeric PNG tiles from https://tile.openstreetmap.org/{z}/{x}/{y}.png. Do not use fetch, API requests, WebSockets, or other network access.
        - For Local Sandbox or Conch raster images, use static quoted <img src="shell://SERVER/absolute/path.png"> sources. Percent-encode spaces in server names and paths. Do not construct shell URLs in JavaScript. Video is unsupported.
        - Interaction state can reset on reload, re-entry, fullscreen transitions, or process restart. Do not rely on persistent storage.
    """.trimIndent()

    /** Shipped HTML preview guidance before embedded HTML file links were added. */
    internal val previousTextBeforeFileEmbedForMigration: String = "\n\n" + """
        $HEADING
        - Use a closed `html-preview` fenced block for an interactive visualization in native Agora chat; it renders automatically only after the closing fence. An `html` fence shows source with a manual Run action.
        - Keep each document self-contained with inline HTML, CSS, JavaScript, and data. HTTPS static JS/CSS/fonts may load only from cdnjs.cloudflare.com, cdn.jsdelivr.net, unpkg.com, or esm.sh, with paths ending in .js, .mjs, .css, .woff, .woff2, or .ttf. Use exact versioned URLs without query strings or redirects.
        - Maps may load numeric PNG tiles from https://tile.openstreetmap.org/{z}/{x}/{y}.png. Do not use fetch, API requests, WebSockets, or other network access.
        - For Local Sandbox or Conch raster images, use static quoted <img src="shell://SERVER/absolute/path.png"> sources. Percent-encode spaces in server names and paths. Do not construct shell URLs in JavaScript. Video is unsupported.
        - Interaction state can reset on reload, re-entry, fullscreen transitions, or process restart. Do not rely on persistent storage.
    """.trimIndent()

    fun item(): PromptTemplateItem = PromptTemplateItem(type = PromptItemType.CUSTOM, value = text)

    internal fun previousItemBeforeFileEmbedForMigration(): PromptTemplateItem =
        PromptTemplateItem(type = PromptItemType.CUSTOM, value = previousTextBeforeFileEmbedForMigration)

    fun isPresent(items: List<PromptTemplateItem>): Boolean = items.any { item ->
        item.type == PromptItemType.CUSTOM && item.value.lineSequence().any { it.trim() == HEADING }
    }
}
