package com.newoether.agora.webui

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiThemeTest {
    private val colors = mapOf("primary" to 0xFF112233.toInt(), "onSurfaceVariant" to 0x80AABBCC.toInt())

    @Test
    fun cssCarriesTheResolvedSchemeAndAppFont() {
        val css = WebUiTheme(dark = true, colors = colors, font = WebUiFont.AppDefault).toCss()
        assertTrue(css.contains("color-scheme:dark"))
        assertTrue(css.contains("--md-primary:#112233;"))
        assertTrue(css.contains("--md-on-surface-variant:#aabbcc80;"))
        assertTrue(css.contains("@font-face{font-family:\"AgoraApp\";src:url(\"${WebUiTheme.FONT_PATH}?v="))
        assertTrue(css.contains("--app-font:\"AgoraApp\","))
    }

    @Test
    fun systemFontSkipsTheFontFace() {
        val css = WebUiTheme(dark = false, colors = colors, font = WebUiFont.System).toCss()
        assertTrue(css.contains("color-scheme:light"))
        assertFalse(css.contains("@font-face"))
        assertFalse(css.contains("AgoraApp"))
    }

    @Test
    fun serverServesThemeCssUncachedAndTheFontOnlyWhenPresent() = testApplication {
        val auth = WebUiAuth(store = io.mockk.mockk(relaxed = true), hasher = WebUiPasswordHasher(iterations = 1_000))
        var font: ByteArray? = "OTTOxxxx".toByteArray()
        application {
            WebUiServer(
                auth = auth,
                readAsset = { null },
                syncSession = { _, _, _ -> },
                themeCss = { ":root{--md-primary:#112233}" },
                readAppFont = { font },
            ).install(this)
        }
        val css = client.get("/theme.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.headers[HttpHeaders.ContentType]!!.startsWith("text/css"))
        assertEquals("no-store", css.headers[HttpHeaders.CacheControl])
        assertEquals(":root{--md-primary:#112233}", css.bodyAsText())

        val otf = client.get(WebUiTheme.FONT_PATH)
        assertEquals("font/otf", otf.headers[HttpHeaders.ContentType])

        font = null
        assertEquals(HttpStatusCode.NotFound, client.get(WebUiTheme.FONT_PATH).status)
    }
    @Test
    fun serverServesTheCodeFontByStyleName() = testApplication {
        val auth = WebUiAuth(store = io.mockk.mockk(relaxed = true), hasher = WebUiPasswordHasher(iterations = 1_000))
        application {
            WebUiServer(
                auth = auth,
                readAsset = { null },
                syncSession = { _, _, _ -> },
                readMonoFont = { style -> if (style == "bold") byteArrayOf(0, 1, 0, 0) else null },
            ).install(this)
        }
        val bold = client.get("${WebUiServer.MONO_FONT_PATH}/bold")
        assertEquals(HttpStatusCode.OK, bold.status)
        assertEquals("font/ttf", bold.headers[HttpHeaders.ContentType])
        assertEquals(HttpStatusCode.NotFound, client.get("${WebUiServer.MONO_FONT_PATH}/light").status)
    }
}
