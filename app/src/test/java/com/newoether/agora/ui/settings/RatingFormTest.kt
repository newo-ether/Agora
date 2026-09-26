package com.newoether.agora.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
class RatingFormTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun upstreamPackageShowsCreditAndOpensOriginalProjectOnlyOnClick() {
        var launched: Intent? = null
        showForm("com.newoether.agora", onLaunch = { launched = it })
        compose.onNodeWithText("Modified Version").assertDoesNotExist()
        compose.onNodeWithText("Agora · Developed by newo-ether").assertIsDisplayed()
        assertEquals(null, launched)
        compose.onNodeWithText("Original project: github.com/newo-ether/Agora").performClick()
        assertEquals(Intent.ACTION_VIEW, launched?.action)
        assertEquals("https://github.com/newo-ether/Agora", launched?.dataString)
    }

    @Test
    fun renamedPackageShowsNoticeAndKeepsRatingControls() {
        var launched: Intent? = null
        showForm("com.youlong.ai", onLaunch = { launched = it })
        compose.onNodeWithText("Modified Version").assertIsDisplayed()
        compose.onNodeWithText("Agora · Developed by newo-ether").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View Original Project").performScrollTo().performClick()
        assertEquals("https://github.com/newo-ether/Agora", launched?.dataString)
        compose.onNodeWithText("Submit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your Name (optional)").assertExists()
        compose.onNodeWithText("Your Email (optional)").assertExists()
        compose.onNodeWithText("Comment (optional)").assertExists()
    }

    @Test
    fun mismatchIsExactAndDoesNotTreatOriginalPrefixOrSuffixAsAuthenticity() {
        assertFalse(ratingUsesDifferentPackage("com.newoether.agora"))
        listOf("", "com.youlong.ai", "com.newoether.agora.fork", "com.newoether.agora.screenshots",
            "com.newoether.agorax", "com.newoether.Agora").forEach {
            assertTrue(it, ratingUsesDifferentPackage(it))
        }
    }

    @Test
    fun modifiedFormRemainsReachableWithShortViewportAndLargeText() {
        showForm("org.example.fork", height = 320, fontScale = 1.8f)
        compose.onNodeWithText("Modified Version").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View Original Project").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Submit").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseNoticeUsesLocalizedDecodedResources() {
        showForm("com.youlong.ai")
        compose.onNodeWithText("当前为修改版本").assertIsDisplayed()
        compose.onNodeWithText("Agora · 由 newo-ether 开发").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("查看原项目").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun everyLocaleHasValidLocalizedUtf8AttributionAndNoLostAuthor() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main/res").isDirectory }
        val locales = File(root, "app/src/main/res").listFiles().orEmpty()
            .filter { File(it, "strings.xml").isFile }
        assertEquals(12, locales.size)
        val keys = setOf("credit", "project", "modified_title", "modified_body", "view_project")
            .map { "rating_origin_${it}_b64" }.toSet()
        val titles = mutableSetOf<String>()
        locales.forEach { locale ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(locale, "strings.xml"))
            val nodes = document.getElementsByTagName("string")
            val found = (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttribute("name") in keys }
                .associate { it.getAttribute("name") to it.textContent }
            assertEquals(locale.name, keys, found.keys)
            val decoded = found.mapValues { (_, encoded) ->
                val value = decodeRatingOriginText(encoded)
                assertTrue(locale.name, value.isNotBlank())
                assertFalse(locale.name, value.contains('\uFFFD'))
                assertEquals(encoded, Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)))
                value
            }
            assertTrue(locale.name, decoded.getValue("rating_origin_credit_b64").contains("newo-ether"))
            assertTrue(locale.name, decoded.getValue("rating_origin_modified_body_b64").contains("Agora"))
            assertTrue(locale.name, decoded.getValue("rating_origin_modified_body_b64").contains("newo-ether"))
            assertTrue(locale.name, decoded.getValue("rating_origin_project_b64").contains("github.com/newo-ether/Agora"))
            titles += decoded.getValue("rating_origin_modified_title_b64")
        }
        assertEquals("Each shipped locale has a translated notice title", 12, titles.size)
    }

    private fun showForm(
        packageName: String,
        height: Int = 1200,
        fontScale: Float = 1f,
        onLaunch: (Intent) -> Unit = {},
    ) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getPackageName(): String = packageName
            override fun startActivity(intent: Intent) = onLaunch(intent)
        }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density, fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(height.dp).verticalScroll(rememberScrollState())) {
                        RatingForm()
                    }
                }
            }
        }
    }
}
