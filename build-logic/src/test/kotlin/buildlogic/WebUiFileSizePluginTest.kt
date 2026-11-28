package buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebUiFileSizePluginTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `WebUI gate covers authored modules and preserves vendor exclusions`() {
        val root = temporary.newFolder()
        val assets = root.resolve("app/src/main/assets/webui").apply { mkdirs() }
        assets.resolve("main.js").writeText("export {};\n")
        assets.resolve("material").mkdirs()
        assets.resolve("material/progress.mjs").writeText("export {};\n")
        assets.resolve("style.css").writeText("body {}\n")
        assets.resolve("vendor").mkdirs()
        assets.resolve("vendor/library.js").writeText("vendor\n".repeat(2000))
        assets.resolve("image.png").writeBytes(byteArrayOf(1, 2, 3))
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply(KotlinSourceSizePlugin::class.java)
        val task = project.tasks.getByName("verifyWebUiFileSize") as VerifyWebUiFileSizeTask
        assertEquals(setOf("main.js", "progress.mjs", "style.css"), task.sourceFiles.files.map { it.name }.toSet())
        val kotlin = project.tasks.getByName("verifyKotlinFileSize")
        assertTrue(kotlin.taskDependencies.getDependencies(kotlin).contains(task))
        val check = project.tasks.register("check").get()
        assertTrue(check.taskDependencies.getDependencies(check).contains(kotlin))
        assertFalse(task.sourceFiles.isEmpty)
        task.verify()
    }

    @Test
    fun `oversized CSS fails the actual Gradle gate without an exception path`() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        val source = project.projectDir.resolve("style.css").apply { writeText("body {}\n".repeat(801)) }
        val task = project.tasks.register("verifyWebUi", VerifyWebUiFileSizeTask::class.java).get()
        task.repositoryRoot.set(project.layout.projectDirectory)
        task.sourceFiles.from(source)
        val failure = assertThrows(GradleException::class.java) { task.verify() }
        assertTrue(failure.message.orEmpty().contains("style.css: 801 lines"))
    }

    @Test
    fun `the physical line boundary accepts CRLF modules at 800 lines`() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        val source = project.projectDir.resolve("module.mjs").apply { writeText("export {};\r\n".repeat(800)) }
        val task = project.tasks.register("verifyWebUi", VerifyWebUiFileSizeTask::class.java).get()
        task.repositoryRoot.set(project.layout.projectDirectory)
        task.sourceFiles.from(source)
        task.verify()
    }
}
