package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellMarkdownMediaLinkTest {
    @Test
    fun posixPathIsAbsoluteFromRoot() {
        assertEquals(ShellMediaLink("tinybox", "/root/out/a.png"), parseShellMediaLink("shell://tinybox/root/out/a.png"))
    }

    @Test
    fun windowsDrivePathStaysDriveRelative() {
        assertEquals(ShellMediaLink("Workstation", "F:/out/a.png"), parseShellMediaLink("shell://Workstation/F:/out/a.png"))
    }

    @Test
    fun doubleSlashKeepsSingleRoot() {
        assertEquals("/home/a.png", parseShellMediaLink("shell://box//home/a.png")?.path)
    }

    @Test
    fun percentEscapesDecodeAsUtf8InServerAndPath() {
        val link = parseShellMediaLink("shell://Local%20Sandbox/home/agora/%E5%9B%BE%20a+b.png")
        assertEquals(ShellMediaLink(LOCAL_SANDBOX_SERVER, "/home/agora/图 a+b.png"), link)
    }

    @Test
    fun angleBracketsSchemeCaseAndRawSpacesAreAccepted() {
        assertEquals(
            ShellMediaLink("Local Sandbox", "/home/agora/a b.png"),
            parseShellMediaLink("<SHELL://Local Sandbox/home/agora/a b.png>"),
        )
    }

    @Test
    fun keyIgnoresServerCaseButNotPathCase() {
        val upper = requireNotNull(parseShellMediaLink("shell://WORKSTATION/F:/A.png"))
        val lower = requireNotNull(parseShellMediaLink("shell://workstation/F:/A.png"))
        val otherPath = requireNotNull(parseShellMediaLink("shell://workstation/F:/a.png"))
        assertEquals(upper.key, lower.key)
        assert(lower.key != otherPath.key)
    }

    @Test
    fun otherAndMalformedLinksAreRejected() {
        listOf(
            "https://example.com/a.png",
            "latex://x",
            "/home/a.png",
            "shell://",
            "shell:///a.png",
            "shell://box",
            "shell://box/",
            "shell://%20/a.png",
            "shell://box/a%2",
            "shell://box/a%zz.png",
            "shell://box/a%00.png",
            "shell://box/%FF.png",
        ).forEach { assertNull(it, parseShellMediaLink(it)) }
    }
}
