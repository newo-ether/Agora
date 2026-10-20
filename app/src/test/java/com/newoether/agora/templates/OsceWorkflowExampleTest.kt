package com.newoether.agora.templates

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.SkillManager
import com.newoether.agora.skills.SkillDocument
import com.newoether.agora.skills.SkillConfiguration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OsceWorkflowExampleTest {
    private fun example(name: String): String {
        val directory = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "examples/interactive-templates/osce") }.first { it.isDirectory }
        return File(directory, name).readText()
    }
    @Test fun portableInterfacesAndSkillsRestoreWithFilenameRoutingAndNoBundledCorpus() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = InteractiveTemplateRepository(context)
        val manager = SkillManager(context)
        val setup = InteractiveTemplate.decode(example("osce.agora-template.json"))
        val review = InteractiveTemplate.decode(example("review.agora-template.json"))
        val examiner = SkillDocument.decode("osce.md", example("examiner.md"))
        val revision = SkillDocument.decode("targeted-revision.md", example("targeted-revision.md"))
        assertTrue(repository.restoreCatalogue(Json.encodeToString(listOf(setup, review)), replace = true).isEmpty())
        manager.saveDocument(revision, "Revise gaps identified in the examination")
        manager.saveDocument(examiner, "Practise a reference based clinical examination")
        assertEquals(SkillConfiguration("osce", setup.id, review.id, revision.name), manager.document("osce.md").configuration)
        assertNull(repository.get(setup.id).sourceDirectory)
        assertNotNull(repository.get(setup.id).sourceFolderId)
        assertTrue(repository.get(setup.id).sourceFilesAuto)
        assertNull(setup.linkedSkill)
        assertNull(setup.shortcut)
        assertNull(setup.skillFlow)
        assertTrue(setup.resourceFiles.isEmpty())
        assertFalse(setup.answer.encode().contains("invoke_skill"))
        assertFalse(setup.answer.encode().contains("osce.md"))
        assertFalse(setup.answer.encode().contains("targeted-revision.md"))
        assertTrue(setup.answer.jsFunctions.contains("agoraSourceFiles"))
        assertTrue(review.answer.jsFunctions.contains("agoraWorkflowInput"))
        assertEquals(listOf(setup, review).toSet(), repository.list().toSet())
    }
}
