package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Allowed compile/test edges for the maintained production modules, including namespace parents. */
internal val allowedModuleDependencies = mapOf(
    ":" to emptySet(),
    ":core" to emptySet(),
    ":data" to emptySet(),
    ":provider" to emptySet(),
    ":app" to setOf(":core:model", ":core:contracts", ":core:presentation", ":data:database", ":provider:cloud"),
    ":core:model" to emptySet(),
    ":core:contracts" to setOf(":core:model"),
    ":core:presentation" to setOf(":core:model", ":core:contracts"),
    ":data:database" to setOf(":core:model"),
    ":provider:cloud" to setOf(":core:contracts"),
)

abstract class VerifyModuleBoundariesTask : DefaultTask() {
    @get:Input
    abstract val projectPaths: SetProperty<String>

    /** Keys contain the source project and configuration, separated by '/'. */
    @get:Input
    abstract val declaredDependencies: MapProperty<String, List<String>>

    internal fun captureProjects(root: Project) {
        projectPaths.set(root.allprojects.map { it.path }.toSet())
        // Inspect declarations only. Never resolve configurations or their task dependencies.
        declaredDependencies.set(root.allprojects.flatMap { candidate ->
            candidate.configurations.map { configuration ->
                "${candidate.path}/${configuration.name}" to configuration.dependencies
                    .withType(ProjectDependency::class.java)
                    .filterNot { dependency ->
                        // AGP links a test component to its own production variant here. These
                        // classpaths are also declarable, so that flag cannot identify this link.
                        dependency.path == candidate.path && configuration.isCanBeResolved &&
                            !configuration.isCanBeConsumed &&
                            Regex(".+(?:UnitTest|AndroidTest)(?:Compile|Runtime)Classpath")
                                .matches(configuration.name)
                    }
                    .map { it.path }.distinct().sorted()
            }
        }.toMap())
    }

    @TaskAction
    fun verify() {
        val paths = projectPaths.get()
        val declarations = declaredDependencies.get()
        val required = allowedModuleDependencies.keys.filter { it == ":app" || it.count { char -> char == ':' } > 1 }
        val violations = buildList {
            (paths - allowedModuleDependencies.keys).sorted().forEach { path ->
                add("$path is not in the reviewed module baseline")
            }
            (required - paths).sorted().forEach { path ->
                add("$path is missing from the reviewed module baseline")
            }
            declarations.toSortedMap().forEach { (origin, targets) ->
                val source = origin.substringBefore('/')
                targets.forEach { target ->
                    if (target !in allowedModuleDependencies[source].orEmpty()) {
                        add("$origin -> $target is not an allowed project dependency")
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(buildString {
                appendLine("Module boundary verification failed:")
                violations.forEach { appendLine(" - $it") }
                append("Review ownership in development/modularization-plan.md before changing the allowed graph.")
            })
        }
        val edges = declarations.flatMap { (origin, targets) ->
            targets.map { origin.substringBefore('/') to it }
        }.toSet()
        logger.lifecycle(
            "Module boundaries verified: {} production modules, {} project edges; allowed graph is acyclic",
            required.size,
            edges.size,
        )
    }
}

class ModuleBoundariesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "buildlogic.module-boundaries must be applied to the root project"
        }
        val verify = project.tasks.register("verifyModuleBoundaries", VerifyModuleBoundariesTask::class.java) {
            group = "verification"
            description = "Verifies declared project dependencies against the maintained module boundaries."
        }
        project.gradle.projectsEvaluated {
            // Freeze plain task inputs after every build script has declared its dependencies.
            verify.get().captureProjects(project)
        }
        project.allprojects.forEach { candidate ->
            candidate.tasks.matching {
                it.name == "check" || it.name == "test" || it.name == "preBuild"
            }.configureEach {
                dependsOn(verify)
            }
        }
    }
}
