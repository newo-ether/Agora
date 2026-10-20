package com.newoether.agora.templates

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.NativeBackupArchive
import com.newoether.agora.data.SkillManager
import com.newoether.agora.tool.SkillToolProvider
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/** Optional real-pack acceptance: selected local source only, no provider or HTTP dependencies. */
@RunWith(RobolectricTestRunner::class)
class OfflineOscePackTest {
    private fun ZipFile.names() = entries().asSequence().map { it.name }.toList()
    private fun ZipFile.bytes(name: String): ByteArray? = getEntry(name)?.let { getInputStream(it).use { input -> input.readBytes() } }

    @Test fun downloadedPackUsesLocalSkillTools() = runTest {
        val packPath = System.getProperty("agora.osce.pack") ?: System.getenv("AGORA_OSCE_PACK")
        org.junit.Assume.assumeTrue("Personal downloaded source pack was not supplied", packPath != null)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = SkillManager(context)
        val importedNames = mutableListOf<String>()
        val templates = InteractiveTemplateRepository(context)
        try {
            ZipFile(File(checkNotNull(packPath))).use { archive ->
                val manifest = archive.bytes("manifest.json")!!.decodeToString()
                assertTrue(manifest.contains("templates"))
                val sources = archive.names().filter { it.startsWith("memories/skill_db/ztf-") && it.endsWith(".md") }
                assertEquals(300, sources.size)
                assertFalse(archive.names().any { it.contains("api_keys") })
                assertFalse(archive.names().any { it.startsWith("conv/") })
                val catalogue = archive.bytes(InteractiveTemplateRepository.ARCHIVE_ENTRY)!!.decodeToString()
                assertTrue(templates.restoreCatalogue(catalogue, replace = false).isEmpty())
                val template = templates.list().single { it.name == "Zero to Finals OSCE" }
                assertEquals("zero-to-finals-osce.md", template.linkedSkill)
                assertTrue(template.answer.html.contains("type=\"range\""))
                sources.forEach { entry ->
                    val name = entry.substringAfterLast('/')
                    val body = archive.bytes(entry)!!.decodeToString()
                    assertTrue(body.contains("Source: Zero to Finals"))
                    assertTrue(body.contains("Original URL: https://zerotofinals.com/"))
                    assertTrue(body.contains("Downloaded:"))
                    manager.createFile(name, body)
                    importedNames += name
                }
                val examiner = archive.bytes("memories/skill_db/zero-to-finals-osce.md")!!.decodeToString()
                assertTrue(examiner.contains("Ask exactly ONE focused question"))
                manager.createFile("zero-to-finals-osce.md", examiner)
                importedNames += "zero-to-finals-osce.md"
                val provider = SkillToolProvider(manager)
                val ctx = GenerationContext(skillReadAccess = true)
                val read = provider.executeOneShotResult("read_skill_file", """{"name":"ztf-m-cardiology-acs.md"}""", ctx)
                assertFalse(read.isError)
                assertTrue(read.text.contains("###"))
                assertTrue(read.text.contains("**"))
                assertTrue(read.text.contains("Coronary Artery Anatomy"))
                assertTrue(read.text.contains("300mg") || read.text.contains("300 mg"))
                assertFalse(read.text.contains("# Appendicitis"))
                val surgery = provider.executeOneShotResult("read_skill_file", """{"name":"ztf-s-general-appendicitis.md"}""", ctx)
                assertFalse(surgery.isError)
                assertTrue(surgery.text.startsWith("# Appendicitis"))
                assertFalse(surgery.text.contains("# Acute Coronary Syndrome"))
                val missing = runCatching { provider.executeOneShotResult("read_skill_file", """{"name":"missing-condition.md"}""", ctx) }
                assertTrue(missing.isFailure || missing.getOrThrow().isError)
            }
        } finally {
            importedNames.forEach { manager.deleteFile(it) }
            templates.list().filter { it.name == "Zero to Finals OSCE" }.forEach { templates.delete(it.id) }
        }
    }
}

/** Keep native archive validation outside Robolectric's older Commons Compress classloader. */
class OfflineOsceArchiveTest {
    @Test fun downloadedPackOpensAsNativeArchive() {
        val path = System.getenv("AGORA_OSCE_PACK")
        org.junit.Assume.assumeTrue("Personal source pack was not supplied", path != null)
        NativeBackupArchive.open(FileInputStream(File(checkNotNull(path))).channel).use { archive ->
            assertEquals(300, archive.names().count { it.startsWith("memories/skill_db/ztf-") })
            assertNotNull(archive.bytes(InteractiveTemplateRepository.ARCHIVE_ENTRY))
        }
    }
}
