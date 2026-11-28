package buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleBoundariesPluginTest {
    private fun projectGraph(): Map<String, Project> {
        val root = ProjectBuilder.builder().withName("Agora").build()
        val projects = linkedMapOf(":" to root)
        allowedModuleDependencies.keys.filter { it != ":" }.sortedBy { it.count { char -> char == ':' } }
            .forEach { path ->
                val parent = path.substringBeforeLast(':').ifEmpty { ":" }
                projects[path] = ProjectBuilder.builder()
                    .withName(path.substringAfterLast(':'))
                    .withParent(projects.getValue(parent))
                    .build()
            }
        root.pluginManager.apply(ModuleBoundariesPlugin::class.java)
        return projects
    }

    private fun Project.addEdge(target: String, configuration: String = "implementation") {
        configurations.maybeCreate(configuration)
        dependencies.add(configuration, dependencies.project(mapOf("path" to target)))
    }

    private fun Map<String, Project>.gate(): VerifyModuleBoundariesTask =
        (getValue(":").tasks.getByName("verifyModuleBoundaries") as VerifyModuleBoundariesTask).also {
            it.captureProjects(getValue(":"))
        }

    @Test
    fun `accepted graph passes without resolving a configuration`() {
        val projects = projectGraph()
        var resolved = false
        allowedModuleDependencies.forEach { (source, targets) ->
            val project = projects.getValue(source)
            project.configurations.maybeCreate("implementation").incoming.beforeResolve { resolved = true }
            targets.forEach { project.addEdge(it) }
        }
        val gate = projects.gate()
        gate.verify()
        assertEquals(10, gate.declaredDependencies.get().values.sumOf { it.size })
        assertFalse(resolved)
    }

    @Test
    fun `library to app dependency fails even in test configuration`() {
        val projects = projectGraph()
        projects.getValue(":core:model").addEdge(":app", "testImplementation")
        val failure = assertThrows(GradleException::class.java) { projects.gate().verify() }
        assertTrue(failure.message.orEmpty().contains(":core:model/testImplementation -> :app"))
    }

    @Test
    fun `cross layer dependency fails while approved model edge remains allowed`() {
        val projects = projectGraph()
        projects.getValue(":data:database").addEdge(":core:model")
        projects.getValue(":data:database").addEdge(":provider:cloud", "debugImplementation")
        val failure = assertThrows(GradleException::class.java) { projects.gate().verify() }
        assertTrue(failure.message.orEmpty().contains(":data:database/debugImplementation -> :provider:cloud"))
        assertFalse(failure.message.orEmpty().contains(":data:database/implementation -> :core:model"))
    }

    @Test
    fun `unreviewed project fails even without dependencies`() {
        val projects = projectGraph()
        ProjectBuilder.builder().withName("runtime").withParent(projects.getValue(":")).build()
        val failure = assertThrows(GradleException::class.java) { projects.gate().verify() }
        assertTrue(failure.message.orEmpty().contains(":runtime is not in the reviewed module baseline"))
    }

    @Test
    fun `self and namespace edges are rejected`() {
        val projects = projectGraph()
        projects.getValue(":core:model").addEdge(":core:model")
        projects.getValue(":core").addEdge(":core:model")
        val failure = assertThrows(GradleException::class.java) { projects.gate().verify() }
        assertTrue(failure.message.orEmpty().contains(":core:model/implementation -> :core:model"))
        assertTrue(failure.message.orEmpty().contains(":core/implementation -> :core:model"))
    }

    @Test
    fun `Android test component links pass without exempting cross project dependencies`() {
        val projects = projectGraph()
        val model = projects.getValue(":core:model")
        val names = listOf(
            "debugUnitTestCompileClasspath", "debugUnitTestRuntimeClasspath",
            "fdroidDebugAndroidTestCompileClasspath", "fdroidDebugAndroidTestRuntimeClasspath",
        )
        names.forEach { name ->
            model.configurations.create(name).apply {
                isCanBeResolved = true
                isCanBeConsumed = false
                isCanBeDeclared = true
            }
            model.addEdge(model.path, name)
        }
        projects.gate().verify()
        model.addEdge(":app", names.first())
        val failure = assertThrows(GradleException::class.java) { projects.gate().verify() }
        assertTrue(failure.message.orEmpty().contains(":core:model/${names.first()} -> :app"))
    }

    @Test
    fun `other resolvable self links and declaration scope self links still fail`() {
        val projects = projectGraph()
        val model = projects.getValue(":core:model")
        model.configurations.create("debugCompileClasspath").apply {
            isCanBeResolved = true
            isCanBeConsumed = false
            isCanBeDeclared = true
        }
        model.addEdge(model.path, "debugCompileClasspath")
        model.addEdge(model.path, "testImplementation")
        val failure = assertThrows(GradleException::class.java) { projects.gate().verify() }
        assertTrue(failure.message.orEmpty().contains(":core:model/debugCompileClasspath -> :core:model"))
        assertTrue(failure.message.orEmpty().contains(":core:model/testImplementation -> :core:model"))
    }

    @Test
    fun `test classpath names alone do not exempt self links`() {
        val projects = projectGraph()
        val model = projects.getValue(":core:model")
        model.configurations.create("debugUnitTestCompileClasspath").apply {
            isCanBeResolved = false
            isCanBeConsumed = false
        }
        model.addEdge(model.path, "debugUnitTestCompileClasspath")
        val failure = assertThrows(GradleException::class.java) { projects.gate().verify() }
        assertTrue(failure.message.orEmpty().contains(":core:model/debugUnitTestCompileClasspath -> :core:model"))
    }

    @Test
    fun `missing production module is rejected`() {
        val projects = projectGraph()
        val gate = projects.gate()
        gate.projectPaths.set(gate.projectPaths.get() - ":provider:cloud")
        val failure = assertThrows(GradleException::class.java) { gate.verify() }
        assertTrue(failure.message.orEmpty().contains(":provider:cloud is missing"))
    }

    @Test
    fun `root and module lifecycle tasks run the same gate`() {
        val projects = projectGraph()
        val gate = projects.gate()
        for (path in listOf(":", ":app", ":core:model", ":provider:cloud")) {
            for (name in listOf("check", "test", "preBuild")) {
                val task = projects.getValue(path).tasks.register(name).get()
                assertTrue(task.taskDependencies.getDependencies(task).contains(gate))
            }
        }
    }

    @Test
    fun `reviewed graph itself has no cycle or unknown target`() {
        fun visit(path: String, ancestors: Set<String>) {
            assertFalse("Cycle at $path", path in ancestors)
            assertTrue("Unknown module $path", path in allowedModuleDependencies)
            allowedModuleDependencies.getValue(path).forEach { visit(it, ancestors + path) }
        }
        allowedModuleDependencies.keys.forEach { visit(it, emptySet()) }
    }
}
